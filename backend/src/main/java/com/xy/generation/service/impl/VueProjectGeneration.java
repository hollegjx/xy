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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 一次 Vue 源码生成的工具循环；任务终态和 SSE 生命周期仍由 GenerationSession 管理。
final class VueProjectGeneration implements GenerationSession.OutputHandler {
    private static final ToolSpecification READ_FILE = ToolSpecification.builder().name("readFile")
            .description("读取本次工程 src 目录中已有源文件的完整内容")
            .parameters(JsonObjectSchema.builder().addStringProperty("relativePath", "如 src/App.vue")
                    .required("relativePath").additionalProperties(false).build()).build();
    private static final ToolSpecification WRITE_FILE = ToolSpecification.builder().name("writeFile")
            .description("在本次工程的 src 目录写入一个完整源文件，不能修改 src/main.js")
            .parameters(JsonObjectSchema.builder().addStringProperty("relativePath", "如 src/App.vue")
                    .addStringProperty("content", "完整源码").required("relativePath", "content")
                    .additionalProperties(false).build()).build();
    private static final ToolSpecification REPLACE_FRAGMENT = ToolSpecification.builder().name("replaceFragment")
            .description("局部修改已有源文件，按原文替换唯一匹配的片段")
            .parameters(JsonObjectSchema.builder().addStringProperty("relativePath", "如 src/App.vue")
                    .addStringProperty("oldText", "原文件中唯一的非空片段，保留空白和换行")
                    .addStringProperty("newText", "替换后的片段")
                    .required("relativePath", "oldText", "newText").additionalProperties(false).build()).build();
    private static final ToolSpecification DELETE_COMPONENT = ToolSpecification.builder().name("deleteComponent")
            .description("删除本次工程中不再使用的非入口 .vue 组件，须先清理对它的引用")
            .parameters(JsonObjectSchema.builder().addStringProperty("relativePath", "如 src/components/Notice.vue")
                    .required("relativePath").additionalProperties(false).build()).build();
    private final long userId, appId, generationId;
    private final GeneratedFileManager files;
    private final ObjectMapper mapper;
    private final Map<String, String> template;
    private final VueProjectBuildManager builder;
    private final boolean qualityCheck;
    private VueGenerationWorkflow workflow;
    private GeneratedFileManager.VueBuildPaths buildPaths;
    private boolean changedSource;
    private final Set<String> deletedComponents = new HashSet<>();

    VueProjectGeneration(long userId, long appId, long generationId, GeneratedFileManager files,
            ObjectMapper mapper, Map<String, String> template, VueProjectBuildManager builder, boolean qualityCheck) {
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.files = files;
        this.mapper = mapper;
        this.template = template;
        this.builder = builder;
        this.qualityCheck = qualityCheck;
    }

    @Override
    public void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
        var conversation = new ArrayList<>(messages);
        var history = new StringBuilder();
        if (!qualityCheck) {
            next(model, conversation, session, history, () -> complete(session, history));
            return;
        }
        workflow = new VueGenerationWorkflow(model, mapper, session, conversation,
                () -> {
                    var sources = new java.util.LinkedHashMap<>(template);
                    sources.putAll(files.readVueSources(userId, appId, generationId));
                    return sources;
                }, done -> next(model, conversation, session, history, done),
                () -> complete(session, history), text -> {
                    history.append(text);
                    session.onPartialResponse(text);
                });
        workflow.run();
    }

    @Override
    public void stop() { if (workflow != null) workflow.stop(); }

    private void next(StreamingChatModel model, List<ChatMessage> messages,
            GenerationSession session, StringBuilder history, Runnable done) {
        if (!session.isRunning()) return;
        try {
            model.chat(ChatRequest.builder().messages(List.copyOf(messages))
                    .toolSpecifications(READ_FILE, WRITE_FILE, REPLACE_FRAGMENT, DELETE_COMPONENT).build(),
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
                                var args = mapper.readTree(tool.arguments());
                                if (args == null || !args.path("relativePath").isTextual()) throw new IllegalArgumentException();
                                String path = args.get("relativePath").textValue();
                                String result, progress;
                                switch (tool.name()) {
                                    case "readFile" -> {
                                        result = files.readVueSource(userId, appId, generationId, path);
                                        progress = "\n已读取：" + path + "\n";
                                    }
                                    case "writeFile" -> {
                                        if (!args.path("content").isTextual()) throw new IllegalArgumentException();
                                        files.writeVueSource(userId, appId, generationId, path, args.get("content").textValue());
                                        changedSource = true;
                                        result = "文件已保存：" + path;
                                        progress = "\n已写入：" + path + "\n";
                                    }
                                    case "replaceFragment" -> {
                                        if (!args.path("oldText").isTextual() || !args.path("newText").isTextual())
                                            throw new IllegalArgumentException();
                                        if (!files.replaceVueSourceFragment(userId, appId, generationId, path,
                                                args.get("oldText").textValue(), args.get("newText").textValue())) {
                                            session.fail("原代码片段未找到或匹配多处，未替换，请重试");
                                            return;
                                        }
                                        changedSource = true;
                                        result = "已局部修改：" + path;
                                        progress = "\n" + result + "\n";
                                    }
                                    case "deleteComponent" -> {
                                        files.deleteVueComponent(userId, appId, generationId, path);
                                        deletedComponents.add(path);
                                        changedSource = true;
                                        result = "已删除组件：" + path;
                                        progress = "\n" + result + "\n";
                                    }
                                    default -> throw new IllegalArgumentException();
                                }
                                history.append(progress);
                                session.onPartialResponse(progress);
                                if (!session.isRunning()) return;
                                messages.add(ToolExecutionResultMessage.from(tool, result));
                            }
                        } catch (IOException | RuntimeException error) {
                            session.fail("Vue 工程文件操作失败，请检查工具参数和文件路径后重试");
                            return;
                        }
                        else messages.add(reply);
                    }
                    if (hasTools) next(model, messages, session, history, done);
                    else done.run();
                }
            });
        } catch (RuntimeException error) {
            session.onError(error);
        }
    }

    private void complete(GenerationSession session, StringBuilder history) {
        if (!changedSource) {
            session.fail("本次未写入 Vue 源码，请重试");
            return;
        }
        try {
            if (!deletedComponents.isEmpty()) {
                String message;
                try {
                    message = VueComponentReferences.findError(
                            files.readVueSources(userId, appId, generationId), deletedComponents);
                } catch (IOException error) {
                    session.fail("无法读取本次工程源码以检查组件引用，请重试");
                    return;
                }
                if (message != null) {
                    session.fail(message);
                    return;
                }
            }
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
