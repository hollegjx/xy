package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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
final class VueGenerationWorkflow {
    private final StreamingChatModel model;
    private final ObjectMapper mapper;
    private final GenerationSession session;
    private final List<ChatMessage> conversation;
    private final Callable<Map<String, String>> sources;
    private final Consumer<Runnable> generate;
    private final Runnable build;
    private final Consumer<String> progress;
    private CompletableFuture<Map<String, Object>> pending;

    VueGenerationWorkflow(StreamingChatModel model, ObjectMapper mapper, GenerationSession session,
            List<ChatMessage> conversation, Callable<Map<String, String>> sources,
            Consumer<Runnable> generate, Runnable build, Consumer<String> progress) {
        this.model = model;
        this.mapper = mapper;
        this.session = session;
        this.conversation = conversation;
        this.sources = sources;
        this.generate = generate;
        this.build = build;
        this.progress = progress;
    }

    void run() {
        try {
            new StateGraph<AgentState>(AgentState::new)
                    .addNode("generate", state -> generationNode(Map.of()))
                    .addNode("check", state -> check())
                    .addNode("repair", state -> {
                        progress.accept("\n正在修复（最多一次）…\n");
                        conversation.add(UserMessage.from("请用文件工具修复当前工程的检查问题，保留其他内容：\n"
                                + state.<String>value("report").orElseThrow()));
                        return generationNode(Map.of("repaired", true));
                    })
                    .addNode("build", node_async(state -> {
                        if (session.isRunning()) {
                            progress.accept("\n检查通过。\n");
                            build.run();
                        }
                        return Map.of();
                    }))
                    .addNode("fail", node_async(state -> {
                        session.fail("自动修复一次后，Vue 工程仍未通过 AI 质检，请重试");
                        return Map.of();
                    }))
                    .addEdge(START, "generate").addEdge("generate", "check")
                    .addConditionalEdges("check", edge_async(state ->
                            state.<Boolean>value("valid").orElse(false) ? "build"
                                    : state.<Boolean>value("repaired").orElse(false) ? "fail" : "repair"),
                            Map.of("build", "build", "repair", "repair", "fail", "fail"))
                    .addEdge("repair", "check").addEdge("build", END).addEdge("fail", END)
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
        progress.accept("\n正在检查 Vue 工程…\n");
        try {
            String prompt = new ClassPathResource("prompts/vue-quality.txt").getContentAsString(StandardCharsets.UTF_8);
            String requirement = conversation.stream().filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast).map(UserMessage::singleText).reduce((a, b) -> a + "\n" + b).orElse("");
            var request = ChatRequest.builder().messages(SystemMessage.from(prompt),
                    UserMessage.from("用户需求：\n" + requirement + "\n本次工程源码：\n"
                            + mapper.writeValueAsString(sources.call()))).build();
            if (!session.isRunning()) return future;
            model.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(PartialResponse response, PartialResponseContext context) {
                    session.trackHandle(context.streamingHandle());
                }
                @Override
                public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
                    session.trackHandle(context.streamingHandle());
                }
                @Override
                public void onPartialToolCall(PartialToolCall call, PartialToolCallContext context) {
                    session.trackHandle(context.streamingHandle());
                }
                @Override
                public void onError(Throwable error) { session.onError(error); }
                @Override
                public void onCompleteResponse(ChatResponse response) {
                    if (!session.isRunning()) return;
                    try {
                        JsonNode report = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
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
                }
            });
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
