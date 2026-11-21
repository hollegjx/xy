package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.FinishReason;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.core.io.ClassPathResource;

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
        for (ChatMessage message : conversation) {
            if (message instanceof UserMessage user) {
                if (message == conversation.getLast()) context.append("本次用户需求：\n");
                context.append(user.singleText()).append('\n');
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

    void run() {
        try {
            new StateGraph<AgentState>(AgentState::new)
                    .addNode("generate", state -> generationNode(Map.of()))
                    .addNode("check", state -> check())
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
                    .addEdge(START, "generate").addEdge("generate", "check")
                    .addConditionalEdges("check", edge_async(state ->
                            state.<Boolean>value("valid").orElse(false) ? "approved"
                                    : state.<Boolean>value("repaired").orElse(false) ? "fail" : "repair"),
                            Map.of("approved", "approved", "repair", "repair", "fail", "fail"))
                    .addConditionalEdges("approved", edge_async(state -> build == null ? "deliver" : "build"),
                            Map.of("build", "build", "deliver", "deliver"))
                    .addEdge("build", "deliver")
                    .addEdge("repair", "check").addEdge("deliver", END).addEdge("fail", END)
                    .compile().invoke(Map.of());
        } catch (Exception error) {
            session.fail("AI 质检流程执行失败，请重试");
        }
    }

    private CompletableFuture<Map<String, Object>> generationNode(Map<String, Object> result) {
        var future = beginNode();
        if (!future.isDone()) generate.accept(() -> future.complete(result));
        return future;
    }

    private CompletableFuture<Map<String, Object>> check() {
        var future = beginNode();
        if (future.isDone()) return future;
        progress.accept("\n正在检查 " + modeLabel + "…\n");
        try {
            String prompt = new ClassPathResource("prompts/generation-quality.txt").getContentAsString(StandardCharsets.UTF_8);
            var request = ChatRequest.builder().messages(SystemMessage.from(prompt + "\n本次生成类型：" + mode.getValue()),
                    UserMessage.from(reviewContext + "本次候选源码：\n" + sources.call())).build();
            if (!session.isRunning()) return future;
            model.chat(request, session.modelStep(false, response -> {
                try {
                    JsonNode report = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(response.aiMessage().text());
                    if (response.finishReason() != FinishReason.STOP || report == null
                            || !report.path("isValid").isBoolean()
                            || !stringArray(report.path("errors")) || !stringArray(report.path("suggestions"))
                            || report.get("isValid").booleanValue() != report.get("errors").isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                    future.complete(Map.of("valid", report.get("isValid").booleanValue(),
                            "report", report.get("errors") + "\n建议：" + report.get("suggestions")));
                } catch (Exception error) {
                    session.fail("AI 质检报告无效，请重试");
                }
            }));
        } catch (Exception error) {
            session.fail("AI 质检未完成，无法读取源码或调用模型，请重试");
        }
        return future;
    }

    private boolean stringArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode item : node) if (!item.isTextual()) return false;
        return true;
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
