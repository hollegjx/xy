package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 一次 Vue 源码生成的工具循环；任务终态和 SSE 生命周期仍由 GenerationSession 管理。
final class VueProjectGeneration implements GenerationSession.OutputHandler {
    private static final ToolSpecification WRITE_FILE = ToolSpecification.builder().name("writeFile")
            .description("在本次工程的 src 目录写入一个完整源文件，不能修改 src/main.js")
            .parameters(JsonObjectSchema.builder().addStringProperty("relativePath", "如 src/App.vue")
                    .addStringProperty("content", "完整源码").required("relativePath", "content")
                    .additionalProperties(false).build()).build();
    private final long userId, appId, generationId;
    private final GeneratedFileManager files;
    private final ObjectMapper mapper;
    private final Map<String, String> template;
    private final VueProjectBuildManager builder;
    private GeneratedFileManager.VueBuildPaths buildPaths;

    VueProjectGeneration(long userId, long appId, long generationId, GeneratedFileManager files,
            ObjectMapper mapper, Map<String, String> template, VueProjectBuildManager builder) {
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.files = files;
        this.mapper = mapper;
        this.template = template;
        this.builder = builder;
    }

    @Override
    public void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
        next(model, new ArrayList<>(messages), session, new StringBuilder());
    }

    private void next(StreamingChatModel model, List<ChatMessage> messages,
            GenerationSession session, StringBuilder history) {
        if (!session.isRunning()) return;
        try {
            model.chat(ChatRequest.builder().messages(List.copyOf(messages)).toolSpecifications(WRITE_FILE).build(),
                    new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String text) { session.onPartialResponse(text); }
                @Override
                public void onPartialResponse(PartialResponse text, PartialResponseContext context) {
                    session.onPartialResponse(text, context);
                }
                @Override
                public void onPartialThinking(PartialThinking text, PartialThinkingContext context) {
                    session.onPartialThinking(text, context);
                }
                @Override
                public void onPartialToolCall(PartialToolCall call, PartialToolCallContext context) {
                    session.onPartialToolCall(call, context);
                }
                @Override
                public void onError(Throwable error) { session.onError(error); }
                @Override
                public void onCompleteResponse(ChatResponse response) {
                    boolean hasTools;
                    synchronized (session) {
                        if (!session.isRunning()) return;
                        if (response.finishReason() != FinishReason.STOP
                                && response.finishReason() != FinishReason.TOOL_EXECUTION) {
                            session.fail("模型未正常完成输出，请重试");
                            return;
                        }
                        AiMessage reply = response.aiMessage();
                        if (reply.text() != null) history.append(reply.text());
                        hasTools = reply.hasToolExecutionRequests();
                        if (hasTools) try {
                            messages.add(reply);
                            for (var tool : reply.toolExecutionRequests()) {
                                if (!"writeFile".equals(tool.name())) throw new IllegalArgumentException();
                                var args = mapper.readTree(tool.arguments());
                                if (args == null || !args.path("relativePath").isTextual()
                                        || !args.path("content").isTextual()) throw new IllegalArgumentException();
                                String path = args.get("relativePath").textValue();
                                files.writeVueSource(userId, appId, generationId, path, args.get("content").textValue());
                                String progress = "\n已写入：" + path + "\n";
                                history.append(progress);
                                session.onPartialResponse(progress);
                                if (!session.isRunning()) return;
                                messages.add(ToolExecutionResultMessage.from(tool, "文件已保存：" + path));
                            }
                        } catch (IOException | RuntimeException error) {
                            session.fail("Vue 工程文件写入失败，请检查工具参数和文件路径后重试");
                            return;
                        }
                    }
                    if (hasTools) next(model, messages, session, history);
                    else complete(session, history);
                }
            });
        } catch (RuntimeException error) {
            session.onError(error);
        }
    }

    private void complete(GenerationSession session, StringBuilder history) {
        try {
            if (builder.enabled()) {
                synchronized (session) {
                    if (!session.isRunning()) return;
                    files.finishVueProject(userId, appId, generationId, template);
                    buildPaths = files.prepareVueBuild(userId, appId, generationId);
                    String progress = "\n源码已保存，正在构建 Vue 工程…\n";
                    history.append(progress);
                    session.onPartialResponse(progress);
                }
                if (!session.isRunning()) return;
                builder.build(buildPaths, session::isRunning);
            }
            history.append('\n').append(completedMessage());
            session.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.STOP)
                    .aiMessage(AiMessage.from(history.toString())).build());
        } catch (IllegalArgumentException error) {
            session.fail(invalidOutputMessage());
        } catch (IOException | RuntimeException error) {
            session.fail("Vue 工程构建失败，请检查源码和构建环境后重试");
        }
    }

    @Override
    public void save(String output) throws IOException {
        if (builder.enabled()) files.saveVuePreview(userId, appId, generationId, buildPaths);
        else files.finishVueProject(userId, appId, generationId, template);
    }

    @Override
    public String invalidOutputMessage() { return "Vue 工程缺少完整的 src/App.vue，请重试"; }

    @Override
    public String completedMessage() {
        return builder.enabled() ? "Vue 工程构建完成，可在线预览或下载源码。"
                : "Vue 工程源码已生成，可下载 ZIP；尚未构建，暂不支持在线预览或发布。";
    }
}
