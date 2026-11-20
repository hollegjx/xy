package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.enums.CodeGenTypeEnum;
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
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// 一次 Vue 源码生成的工具循环；任务终态和 SSE 生命周期仍由 GenerationSession 管理。
final class VueProjectGeneration implements GenerationSession.OutputHandler {
    private static final ToolSpecification READ_FILE = ToolSpecification.builder().name("readFile")
            .description("读取 src 下的源码；读取已有 xy-photo.json 时只返回照片来源和许可")
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
    private static final ToolSpecification FIND_PHOTO = ToolSpecification.builder().name("findPhoto")
            .description("首次创建需要内容照片的页面时，在写文件前单独选择一次公开主题，无合适主题时跳过")
            .parameters(JsonObjectSchema.builder().addEnumProperty("theme", OpenversePhotoSearch.themes(), "预设照片主题")
                    .required("theme").additionalProperties(false).build()).build();
    private final OpenversePhotoSearch photoSearch;
    private final long userId, appId, generationId;
    private final GeneratedFileManager files;
    private final ObjectMapper mapper;
    private final Map<String, String> template;
    private final VueProjectBuildManager builder;
    private final boolean qualityCheck;
    private GenerationQualityWorkflow workflow;
    private GeneratedFileManager.VueBuildPaths buildPaths;
    private boolean choosingAssets, photoRequested, illustrationRequested, logoRequested;
    private int remainingDiagramAttempts = 2;
    private boolean changedSource;
    private final Set<String> deletedComponents = new HashSet<>();
    private final VueAssetPrompt assetPrompt = new VueAssetPrompt();

    VueProjectGeneration(long userId, long appId, long generationId, GeneratedFileManager files,
            ObjectMapper mapper, Map<String, String> template, VueProjectBuildManager builder, boolean qualityCheck,
            OpenversePhotoSearch photoSearch) {
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.files = files;
        this.mapper = mapper;
        this.template = template;
        this.builder = builder;
        this.qualityCheck = qualityCheck;
        this.photoSearch = photoSearch;
    }

    @Override
    public void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
        // 已有成功版本的上下文含上一版工程清单；修改时沿用复制过来的素材。
        choosingAssets = messages.stream().noneMatch(AiMessage.class::isInstance);
        var conversation = new ArrayList<>(messages);
        var history = new StringBuilder();
        if (!qualityCheck) {
            next(model, conversation, session, history, () -> complete(session, history));
            return;
        }
        // 在工具写入候选工程之前取出继承的源码；生成对话中的 AI 消息只有文件清单。
        String previousSource;
        try {
            previousSource = choosingAssets ? null : mapper.writeValueAsString(files.readVueSources(userId, appId, generationId));
        } catch (IOException error) {
            session.onContextError(error);
            return;
        }
        workflow = new GenerationQualityWorkflow(CodeGenTypeEnum.VUE_PROJECT, model, mapper, session, conversation, previousSource,
                () -> {
                    var sources = new java.util.LinkedHashMap<>(template);
                    sources.putAll(files.readVueSources(userId, appId, generationId));
                    if (files.hasVuePhoto(userId, appId, generationId)) {
                        sources.put(GeneratedFileManager.VUE_PHOTO, readPhotoAttribution());
                    }
                    return mapper.writeValueAsString(sources);
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
            boolean offerPhoto = choosingAssets && !changedSource && !photoRequested;
            boolean offerIllustration = choosingAssets && !changedSource && !illustrationRequested;
            boolean offerDiagram = choosingAssets && !changedSource && remainingDiagramAttempts > 0;
            boolean offerLogo = choosingAssets && !changedSource && !logoRequested;
            var tools = new ArrayList<>(List.of(READ_FILE, WRITE_FILE, REPLACE_FRAGMENT, DELETE_COMPONENT));
            if (offerPhoto) tools.add(FIND_PHOTO);
            if (offerIllustration) tools.add(VueIllustrations.TOOL);
            if (offerDiagram) tools.add(VueArchitectureDiagram.TOOL);
            if (offerLogo) tools.add(VueBrandLogo.TOOL);
            model.chat(
                ChatRequest.builder().messages(assetPrompt.forGeneration(messages)).toolSpecifications(tools).build(),
                session.modelStep(true, response -> {
                    if (response.finishReason() != FinishReason.STOP && response.finishReason() != FinishReason.TOOL_EXECUTION) {
                        session.fail("模型未正常完成输出，请重试");
                        return;
                    }
                    AiMessage reply = response.aiMessage();
                    int previousAssetCount = assetPrompt.size();
                    boolean preparingAssets = reply.hasToolExecutionRequests() && reply.toolExecutionRequests().stream()
                            .anyMatch(tool -> switch (tool.name()) {
                                case "findPhoto" -> offerPhoto;
                                case "selectIllustration" -> offerIllustration;
                                case "drawArchitecture" -> offerDiagram;
                                case "generateLogo" -> offerLogo;
                                default -> false;
                            });
                    String photoResult = null;
                    if (offerPhoto && reply.hasToolExecutionRequests()) {
                        var request = reply.toolExecutionRequests().stream().filter(t -> "findPhoto".equals(t.name())).findFirst();
                        if (request.isPresent()) {
                            photoRequested = true;
                            try {
                                photoResult = collectPhoto(request.get().arguments(), session, history);
                            } catch (IOException error) {
                                session.fail("无法保存本次工程的配图，请重试");
                                return;
                            }
                        }
                    }
                    boolean hasTools;
                    synchronized (session) {
                        if (!session.isRunning()) return;
                        if (reply.text() != null) history.append(reply.text());
                        hasTools = reply.hasToolExecutionRequests();
                        if (hasTools) try {
                            messages.add(reply);
                            for (var tool : reply.toolExecutionRequests()) {
                                if ("findPhoto".equals(tool.name())) {
                                    messages.add(ToolExecutionResultMessage.from(tool, photoResult == null
                                            ? "本轮不再搜索照片，请沿用工程已有素材或无图布局。" : photoResult));
                                    continue;
                                }
                                if ("selectIllustration".equals(tool.name())) {
                                    String result = "本轮不再选择插画，沿用已有素材或继续编写页面。";
                                    if (offerIllustration && !illustrationRequested && !changedSource) {
                                        illustrationRequested = true;
                                        result = selectIllustration(tool.arguments(), session, history);
                                    }
                                    messages.add(ToolExecutionResultMessage.from(tool, result));
                                    if (!session.isRunning()) return;
                                    continue;
                                }
                                if ("drawArchitecture".equals(tool.name())) {
                                    String result = offerDiagram && !changedSource && remainingDiagramAttempts > 0
                                            ? drawArchitecture(tool.arguments(), session, history)
                                            : "本轮不再绘制架构图，沿用已有图或用文字说明关系。";
                                    messages.add(ToolExecutionResultMessage.from(tool, result));
                                    if (!session.isRunning()) return;
                                    continue;
                                }
                                if ("generateLogo".equals(tool.name())) {
                                    String result = "本轮不再绘制 Logo，请沿用已有标志或品牌文字。";
                                    if (offerLogo && !logoRequested && !changedSource) {
                                        logoRequested = true;
                                        result = generateLogo(tool.arguments(), session, history);
                                    }
                                    messages.add(ToolExecutionResultMessage.from(tool, result));
                                    if (!session.isRunning()) return;
                                    continue;
                                }
                                if (preparingAssets && Set.of("writeFile", "replaceFragment", "deleteComponent").contains(tool.name())) {
                                    messages.add(ToolExecutionResultMessage.from(tool,
                                            "本批只准备素材，此文件操作未执行。请根据返回的素材结果重新提交需要的源码操作。"));
                                    continue;
                                }
                                var args = mapper.readTree(tool.arguments());
                                if (args == null || !args.path("relativePath").isTextual()) throw new IllegalArgumentException();
                                String path = args.get("relativePath").textValue();
                                String result, progress;
                                switch (tool.name()) {
                                    case "readFile" -> {
                                        result = GeneratedFileManager.VUE_PHOTO.equals(path) ? readPhotoAttribution()
                                                : files.readVueSource(userId, appId, generationId, path);
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
                                        assetPrompt.remove(path);
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
                            if (assetPrompt.size() > previousAssetCount) {
                                String progress = assetPrompt.progress();
                                history.append(progress);
                                session.onPartialResponse(progress);
                            }
                        } catch (IOException | RuntimeException error) {
                            session.fail("Vue 工程文件操作失败，请检查工具参数和文件路径后重试");
                            return;
                        }
                        else {
                            choosingAssets = false;
                            messages.add(reply);
                        }
                    }
                    if (hasTools) next(model, messages, session, history, done);
                    else done.run();
                }));
        } catch (RuntimeException error) {
            session.onError(error);
        }
    }

    private String drawArchitecture(String arguments, GenerationSession session, StringBuilder history) throws IOException {
        remainingDiagramAttempts--;
        VueArchitectureDiagram.Diagram diagram;
        try {
            diagram = VueArchitectureDiagram.parse(mapper, arguments);
        } catch (IllegalArgumentException invalid) {
            String progress = remainingDiagramAttempts > 0 ? "架构图关系无效，可修正一次。" : "架构图关系仍无效，改用文字说明。";
            history.append('\n').append(progress).append('\n');
            session.onPartialResponse("\n" + progress + "\n");
            return progress + invalid.getMessage() + "；未生成文件，不要引用不存在的组件。";
        }
        var component = VueArchitectureDiagram.bundledRenderer();
        files.writeVueSource(userId, appId, generationId, VueArchitectureDiagram.DATA, mapper.writeValueAsString(diagram));
        files.writeVueSource(userId, appId, generationId, VueArchitectureDiagram.COMPONENT, component);
        files.markVueArchitecture(userId, appId, generationId);
        assetPrompt.add(VueArchitectureDiagram.COMPONENT, "架构图", diagram.nodes().size() + " 个节点、"
                + diagram.edges().size() + " 条连线，组件读取 " + VueArchitectureDiagram.DATA + "，用于系统说明");
        remainingDiagramAttempts = 0;
        String progress = "已绘制架构图：" + diagram.nodes().size() + " 个节点、" + diagram.edges().size() + " 条连线。";
        history.append('\n').append(progress).append('\n');
        session.onPartialResponse("\n" + progress + "\n");
        return progress + "请在 src/App.vue 中 import XyArchitecture from './components/XyArchitecture.vue'，"
                + "在页面相应位置使用 <XyArchitecture />。组件及关系数据已保存，无须重写。";
    }

    private String generateLogo(String arguments, GenerationSession session, StringBuilder history) throws IOException {
        var source = VueBrandLogo.render(mapper, arguments);
        String progress, result;
        if (source.isPresent()) {
            files.writeVueSource(userId, appId, generationId, VueBrandLogo.COMPONENT, source.get());
            assetPrompt.add(VueBrandLogo.COMPONENT, "品牌 Logo", "在导航或品牌区使用该组件");
            progress = "已生成品牌 Logo。";
            result = "在 src/App.vue 中 import XyLogo from './components/XyLogo.vue'，并在品牌区使用 <XyLogo />。";
        } else {
            progress = "Logo 图形无效，改用品牌文字。";
            result = "未生成 Logo 组件，请显示品牌名称，不要引用 XyLogo.vue。";
        }
        history.append('\n').append(progress).append('\n');
        session.onPartialResponse("\n" + progress + "\n");
        return result;
    }

    private String selectIllustration(String arguments, GenerationSession session, StringBuilder history) throws IOException {
        var selected = VueIllustrations.select(mapper, arguments);
        String progress, result;
        if (selected.isPresent()) {
            var illustration = selected.get();
            files.writeVueSource(userId, appId, generationId, VueIllustrations.COMPONENT, illustration.source());
            assetPrompt.add(VueIllustrations.COMPONENT, "主题插画", illustration.label() + "主题，在对应介绍区域使用该组件");
            progress = "已准备插画：" + illustration.label();
            result = progress + "。请在 src/App.vue 中 import XyIllustration from './components/XyIllustration.vue'，"
                    + "在适当位置使用 <XyIllustration />。该组件已存在，无须重写。";
        } else {
            progress = "没有匹配的内置插画，继续生成。";
            result = progress + "不要引用未提供的插画组件。";
        }
        history.append('\n').append(progress).append('\n');
        session.onPartialResponse("\n" + progress + "\n");
        return result;
    }

    private String collectPhoto(String arguments, GenerationSession session, StringBuilder history) throws IOException {
        if (!session.isRunning()) return "";
        session.onPartialResponse("\n正在查找内容配图…\n");
        String theme = "";
        try {
            var args = mapper.readTree(arguments);
            if (args != null) theme = args.path("theme").asText("");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ignored) {
            // 非必要素材：工具参数不完整时仍可生成无图布局。
        }
        // 图库访问可能较慢，不能占用负责心跳和取消的 session 锁。
        Optional<OpenversePhotoSearch.Photo> photo = photoSearch.find(theme);
        synchronized (session) {
            if (!session.isRunning()) return "";
            String result;
            if (photo.isPresent()) {
                files.saveVuePhoto(userId, appId, generationId, mapper.writeValueAsString(photo.get()));
                assetPrompt.add(GeneratedFileManager.VUE_PHOTO, "内容照片", theme + " 主题，绑定 photo.src 显示；"
                        + "许可 " + photo.get().license() + "，署名使用 photo.source");
                result = "已保存内容配图：xy-photo.json。在 src/App.vue 通过 import photo from '../xy-photo.json' 引入，"
                        + "绑定 photo.src 展示图片。来源和许可：" + mapper.writeValueAsString(photo.get().attribution());
            } else {
                result = "未取得可用配图，继续生成无图布局，请勿引用图片文件或外链。";
            }
            String progress = photo.isPresent() ? "\n已准备本地内容配图。\n" : "\n未取得可用配图，继续生成。\n";
            history.append(progress);
            session.onPartialResponse(progress);
            return result;
        }
    }

    private String readPhotoAttribution() throws IOException {
        var attribution = mapper.readValue(files.readVuePhoto(userId, appId, generationId), OpenversePhotoSearch.Attribution.class);
        return mapper.writeValueAsString(attribution);
    }

    private String candidateError() {
        try {
            var sources = files.readVueSources(userId, appId, generationId);
            String graphError = VueArchitectureDiagram.findError(mapper, sources, files.hasVueArchitecture(userId, appId, generationId));
            if (graphError != null) return graphError;
            if (logoRequested && !sources.containsKey(VueBrandLogo.COMPONENT)
                    && VueComponentReferences.references(sources, VueBrandLogo.COMPONENT)) {
                return "页面仍引用不存在的 XyLogo.vue，请改用品牌文字或补全组件后重试";
            }
            return deletedComponents.isEmpty() ? null : VueComponentReferences.findError(sources, deletedComponents);
        } catch (NoSuchFileException missing) {
            return invalidOutputMessage();
        } catch (IOException error) {
            return "无法读取本次工程源码，请重试";
        }
    }

    private void complete(GenerationSession session, StringBuilder history) {
        if (!changedSource) {
            session.fail("本次未写入 Vue 源码，请重试");
            return;
        }
        try {
            String error = candidateError();
            if (error != null) {
                session.fail(error);
                return;
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
