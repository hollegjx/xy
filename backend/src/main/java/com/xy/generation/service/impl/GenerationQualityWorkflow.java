package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;

import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

// 图在生成虚拟线程中等待节点；模型回调只完成当前节点，不决定生成的成功版本。
final class GenerationQualityWorkflow {
    private final CodeGenTypeEnum mode;
    private final String modeLabel;
    private final StreamingChatModel model;
    private final ObjectMapper mapper;
    private final GenerationSession session;
    private final List<ChatMessage> conversation;
    private final String reviewContext;
    private final Callable<String> sources;
    private final Consumer<Runnable> generate;
    private final Runnable build;
    private final Runnable deliver;
    private final Consumer<String> progress;
    private CompletableFuture<Map<String, Object>> pending;

    GenerationQualityWorkflow(CodeGenTypeEnum mode, StreamingChatModel model, ObjectMapper mapper, GenerationSession session,
            List<ChatMessage> conversation, String previousSource, Callable<String> sources,
            Consumer<Runnable> generate, Runnable build, Runnable deliver, Consumer<String> progress) {
        this.mode = mode;
        this.modeLabel = switch (mode) {
            case HTML -> "单 HTML 页面";
            case MULTI_FILE -> "多文件网站";
            case VUE_PROJECT -> "Vue 工程";
        };
        this.model = model;
        this.mapper = mapper;
        this.session = session;
        this.conversation = conversation;
        // 此时还没有候选稿或修复报告。两次检查共用原需求和同一份旧稿。
        var context = new StringBuilder();
        for (int i = 0; i < conversation.size(); i++) {
            ChatMessage message = conversation.get(i);
            if (message instanceof UserMessage user) {
                context.append(i == conversation.size() - 1 ? "本次用户需求：\n" : "历史用户消息：\n")
                        .append(user.singleText()).append('\n');
            } else if (message instanceof AiMessage ai && i != conversation.size() - 2) {
                context.append("历史 AI 回复：\n").append(ai.text()).append('\n');
            }
        }
        if (previousSource != null) context.append("上一成功版源码：\n").append(previousSource).append('\n');
        this.reviewContext = context.toString();
        this.sources = sources;
        this.generate = generate;
        this.build = build;
        this.deliver = deliver;
        this.progress = progress;
    }

    void run(NativeWorkflowAssets assets) { run(assets == null ? null : plan -> assets.collect(plan, session), null); }

    void run(Function<WorkflowAssetPlan, String> collect, Supplier<String> vueManifest) {
        try {
            var graph = new StateGraph<AgentState>(AgentState::new)
                    .addNode("generate", state -> generationNode(Map.of()))
                    .addNode("check", state -> check(vueManifest == null
                            ? state.<String>value("assetManifest").orElse("") : vueManifest.get()))
                    .addNode("repair", state -> {
                        progress.accept("\n正在修复（最多一次）…\n");
                        String instruction = mode == CodeGenTypeEnum.VUE_PROJECT ? "用文件工具修改当前工程"
                                : "以上一条 AI 消息中的候选源码为基准修复，按原格式返回完整源码";
                        conversation.add(UserMessage.from(instruction + "，满足原需求并保留无关内容。检查问题：\n"
                                + state.<String>value("report").orElseThrow()));
                        return generationNode(Map.of("repaired", true));
                    })
                    .addNode("approved", node_async(state -> {
                        if (session.isRunning()) progress.accept("\n检查通过。\n");
                        return Map.of();
                    }))
                    .addNode("build", node_async(state -> {
                        if (session.isRunning()) build.run();
                        return Map.of();
                    }))
                    .addNode("deliver", node_async(state -> {
                        if (session.isRunning()) deliver.run();
                        return Map.of();
                    }))
                    .addNode("fail", node_async(state -> {
                        session.fail("自动修复一次后，" + modeLabel + "仍未通过 AI 质检，请重试");
                        return Map.of();
                    }))
                    .addEdge("generate", "check")
                    .addConditionalEdges("check", edge_async(state ->
                            state.<Boolean>value("valid").orElse(false) ? "approved"
                                    : state.<Boolean>value("repaired").orElse(false) ? "fail" : "repair"),
                            Map.of("approved", "approved", "repair", "repair", "fail", "fail"))
                    .addConditionalEdges("approved", edge_async(state -> build == null ? "deliver" : "build"),
                            Map.of("build", "build", "deliver", "deliver"))
                    .addEdge("build", "deliver")
                    .addEdge("repair", "check").addEdge("deliver", END).addEdge("fail", END);
            if (collect == null) graph.addEdge(START, "generate");
            else graph.addNode("planAssets", state -> planAssets())
                    .addNode("collectAssets", node_async(state -> Map.of("assetManifest",
                            collect.apply(mapper.readValue(state.<String>value("assetPlan").orElseThrow(), WorkflowAssetPlan.class)))))
                    .addNode("enhancePrompt", node_async(state -> {
                        if (session.isRunning()) {
                            String manifest = state.<String>value("assetManifest").orElseThrow();
                            if (!manifest.isEmpty()) {
                                var user = (UserMessage) conversation.getLast();
                                conversation.set(conversation.size() - 1, UserMessage.from(user.singleText() + "\n\n" + manifest));
                            }
                            progress.accept("\n素材准备结束，开始生成页面。\n");
                        }
                        return Map.of();
                    }))
                    .addEdge(START, "planAssets").addEdge("planAssets", "collectAssets")
                    .addEdge("collectAssets", "enhancePrompt").addEdge("enhancePrompt", "generate");
            graph.compile().invoke(Map.of());
        } catch (Exception error) {
            session.fail("AI 质检流程执行失败，请重试");
        }
    }

    private CompletableFuture<Map<String, Object>> planAssets() {
        var node = beginNode();
        if (node.isDone()) return node;
        var prompt = ((UserMessage) conversation.getLast()).singleText();
        CompletableFuture<WorkflowAssetPlan> planned;
        try { planned = WorkflowAssetPlan.request(model, session, node, prompt, mapper, mode == CodeGenTypeEnum.VUE_PROJECT); }
        catch (RuntimeException error) { planned = CompletableFuture.failedFuture(error); }
        planned.whenComplete((plan, error) -> {
            if (node.isDone()) return;
            if (error != null) progress.accept("\n未取得素材计划，继续生成文字布局。\n");
            node.complete(Map.of("assetPlan", error == null ? mapper.valueToTree(plan).toString() : "{}"));
        });
        return node;
    }

    private CompletableFuture<Map<String, Object>> generationNode(Map<String, Object> result) {
        var future = beginNode();
        if (!future.isDone()) generate.accept(() -> future.complete(result));
        return future;
    }

    private CompletableFuture<Map<String, Object>> check(String manifest) {
        var future = beginNode();
        if (future.isDone()) return future;
        progress.accept("\n正在检查 " + modeLabel + "…\n");
        try {
            String context = reviewContext + (manifest.isEmpty() ? "" : manifest + "\n") + "本次候选源码：\n" + sources.call();
            var transport = WorkflowNodeModel.adapt(model, session, future, model.supportedCapabilities(), session::onError);
            GenerationQualityReviewer.check(transport, mapper, mode.getValue(), context).whenComplete((report, error) -> {
                if (!session.isRunning()) return;
                if (error != null) session.fail("AI 质检报告无效，请重试");
                else future.complete(Map.of("valid", report.isValid(), "report",
                        String.join("\n", report.errors()) + "\n建议：" + String.join("\n", report.suggestions())));
            });
        } catch (Exception error) {
            session.fail("AI 质检未完成，无法读取源码或调用模型，请重试");
        }
        return future;
    }

    private CompletableFuture<Map<String, Object>> beginNode() {
        synchronized (session) {
            pending = new CompletableFuture<>();
            if (!session.isRunning()) pending.cancel(false);
            return pending;
        }
    }

    void stop() {
        synchronized (session) {
            if (pending != null) pending.cancel(false);
        }
    }
}
