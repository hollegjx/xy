package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

// 一次 Vue 源码生成的工具循环；任务终态和 SSE 生命周期仍由 GenerationSession 管理。
final class VueProjectGeneration implements GenerationSession.OutputHandler {
    private static final Map<String, String> TOOL_LABELS = Map.of(
            "listFiles", "列出工程文件", "readFile", "读取文件", "writeFile", "写入文件",
            "replaceFragment", "替换代码片段", "deleteComponent", "删除组件",
            "findPhoto", "查找照片", "selectIllustration", "选择插画",
            "drawArchitecture", "绘制架构图", "generateLogo", "生成 Logo");
    private static final Set<String> ASSET_TOOLS = Set.of("findPhoto", "selectIllustration", "drawArchitecture", "generateLogo");
    private static final List<ToolSpecification> SOURCE_TOOLS = ToolSpecifications.toolSpecificationsFrom(SourceTools.class);
    private static final ToolSpecification FIND_PHOTO = ToolSpecification.builder().name("findPhoto")
            .description("首次创建需要照片时，在写文件前选择一次公开主题，可与其他素材工具同批调用")
            .parameters(JsonObjectSchema.builder().addEnumProperty("theme", OpenversePhotoSearch.themes(), "预设照片主题")
                    .required("theme").additionalProperties(false).build()).build();
    private final OpenversePhotoSearch photoSearch;
    private final Executor executor;
    private final ArtworkProviders artwork;
    private FutureTask<Optional<OpenversePhotoSearch.Photo>> pendingPhoto;
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
    private int remainingToolRounds;
    private final SourceTools sourceTools = new SourceTools();
    private boolean changedSource;
    private final Set<String> deletedComponents = new HashSet<>();
    private final VueAssetPrompt assetPrompt = new VueAssetPrompt();

    VueProjectGeneration(long userId, long appId, long generationId, GeneratedFileManager files,
            ObjectMapper mapper, Map<String, String> template, VueProjectBuildManager builder, boolean qualityCheck,
            OpenversePhotoSearch photoSearch, Executor executor, int maxToolRounds, ArtworkProviders artwork) {
        this.remainingToolRounds = maxToolRounds;
        this.artwork = artwork;
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.files = files;
        this.mapper = mapper;
        this.template = template;
        this.builder = builder;
        this.qualityCheck = qualityCheck;
        this.photoSearch = photoSearch;
        this.executor = executor;
    }

    @Override
    public void generate(StreamingChatModel model, List<ChatMessage> messages, GenerationSession session) {
        // 已有成功版本的上下文含上一版工程清单；修改时沿用复制过来的素材。
        choosingAssets = messages.stream().noneMatch(AiMessage.class::isInstance);
        var conversation = new ArrayList<>(messages);
        var history = new StringBuilder();
        if (!qualityCheck) {
            generateTurn(model, conversation, session, history, () -> {
                if (builder.enabled()) build(session, history);
                deliver(session, history);
            });
            return;
        }
        // 质检比较真实源码；生成模型则从文件清单定位文件，按需读取。
        String previousSource;
        try {
            previousSource = choosingAssets ? null : mapper.writeValueAsString(sourcesForModel());
        } catch (IOException error) {
            session.onContextError(error);
            return;
        }
        workflow = new GenerationQualityWorkflow(CodeGenTypeEnum.VUE_PROJECT, model, mapper, session, conversation, previousSource,
                () -> {
                    var sources = new java.util.LinkedHashMap<>(template);
                    sources.putAll(sourcesForModel());
                    if (files.hasVuePhoto(userId, appId, generationId)) {
                        sources.put(GeneratedFileManager.VUE_PHOTO, readPhotoAttribution());
                    }
                    return mapper.writeValueAsString(sources);
                }, done -> generateTurn(model, conversation, session, history, done),
                builder.enabled() ? () -> build(session, history) : null, () -> deliver(session, history), text -> {
                    history.append(text);
                    session.onPartialResponse(text);
                });
        workflow.run();
    }

    @Override
    public void stop() {
        if (pendingPhoto != null) pendingPhoto.cancel(true);
        if (workflow != null) workflow.stop();
    }

    private interface Writer {
        TokenStream write(@dev.langchain4j.service.UserMessage String prompt);
    }

    private void generateTurn(StreamingChatModel model, List<ChatMessage> conversation,
            GenerationSession session, StringBuilder history, Runnable done) {
        if (!session.isRunning()) return;
        var turn = new ToolTurn(session, history);
        var memory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);
        memory.set(conversation.subList(0, conversation.size() - 1));
        var input = ((UserMessage) conversation.getLast()).singleText();
        try {
            // 已停止任务仍可能收到上游滞后的工具回执，拦在 SDK 发起下一次真实模型调用之前。
            StreamingChatModel activeModel = new StreamingChatModel() {
                @Override public ChatRequestParameters defaultRequestParameters() { return model.defaultRequestParameters(); }
                @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                    if (session.isRunning()) model.chat(request, handler);
                }
            };
            var writer = AiServices.builder(Writer.class).streamingChatModel(activeModel).chatMemory(memory)
                    .tools(turn.registry()).chatRequestTransformer(turn::request)
                    .maxToolCallingRoundTrips(Math.max(1, remainingToolRounds))
                    .hallucinatedToolNameStrategy(call -> {
                        session.fail(FILE_ERROR);
                        return ToolExecutionResultMessage.from(call, FILE_ERROR);
                    }).build();
            var events = session.modelStep(true, call -> {
                if (call.name() != null && turn.offered.stream().anyMatch(t -> t.name().equals(call.name()))
                        && turn.announced.add(call.index()))
                    session.onPartialResponse("\n已选择工具：" + TOOL_LABELS.get(call.name()) + "，正在接收参数…\n");
            }, response -> {
                if (!turn.accept(response)) return;
                synchronized (session) {
                    if (!session.isRunning()) return;
                    choosingAssets = false;
                    conversation.clear();
                    conversation.addAll(memory.messages());
                }
                done.run();
            });
            writer.write(input)
                    .onPartialResponseWithContext(events::onPartialResponse)
                    .onPartialThinkingWithContext(events::onPartialThinking)
                    .onPartialToolCallWithContext(events::onPartialToolCall)
                    .onIntermediateResponse(turn::prepare)
                    .onCompleteResponse(events::onCompleteResponse)
                    .onError(turn::error).start();
        } catch (RuntimeException error) {
            turn.error(error);
        }
    }

    private static final String FILE_ERROR = "Vue 工程文件操作失败，请检查工具参数和文件路径后重试";
    private static final String LIMIT_ERROR = "已达到本轮工具调用上限，请缩小修改范围后重试";

    // SDK 负责按顺序执行工具、向对话添加回执并发起下一轮；每轮只提供当前阶段可用的工具。
    private final class ToolTurn {
        final GenerationSession session;
        final StringBuilder history;
        final Set<Integer> announced = new HashSet<>();
        List<ToolSpecification> offered = List.of();
        AssetBatch batch = AssetBatch.EMPTY;
        int previousAssetCount = assetPrompt.size();

        ToolTurn(GenerationSession session, StringBuilder history) {
            this.session = session;
            this.history = history;
        }

        Map<ToolSpecification, ToolExecutor> registry() {
            var registry = new java.util.LinkedHashMap<ToolSpecification, ToolExecutor>();
            for (var spec : SOURCE_TOOLS) registry.put(spec, (call, id) -> execute(spec, call));
            for (var spec : List.of(FIND_PHOTO, ThemeIllustrations.TOOL, VueArchitectureDiagram.TOOL, VueBrandLogo.TOOL))
                registry.put(spec, (call, id) -> session.isRunning() ? batch.receipts().get(call) : "任务已结束");
            return registry;
        }

        ChatRequest request(ChatRequest request) {
            synchronized (session) {
                if (!session.isRunning()) return request;
                if (assetPrompt.size() > previousAssetCount) report(assetPrompt.progress());
                previousAssetCount = assetPrompt.size();
                announced.clear();
                var tools = new ArrayList<>(SOURCE_TOOLS);
                if (choosingAssets && !changedSource) {
                    if (!photoRequested) tools.add(FIND_PHOTO);
                    if (!illustrationRequested) tools.add(ThemeIllustrations.TOOL);
                    if (remainingDiagramAttempts > 0) tools.add(VueArchitectureDiagram.TOOL);
                    if (!logoRequested) tools.add(VueBrandLogo.TOOL);
                }
                offered = tools;
                return ChatRequest.builder().messages(assetPrompt.forGeneration(request.messages())).toolSpecifications(tools).build();
            }
        }

        boolean accept(ChatResponse response) {
            if (!session.isRunning()) return false;
            if (response.finishReason() != FinishReason.STOP && response.finishReason() != FinishReason.TOOL_EXECUTION) {
                session.fail("模型未正常完成输出，请重试");
                return false;
            }
            if (response.aiMessage().text() != null) history.append(response.aiMessage().text());
            return true;
        }

        void prepare(ChatResponse response) {
            if (!accept(response)) return;
            if (remainingToolRounds-- <= 0) { session.fail(LIMIT_ERROR); return; }
            try {
                batch = prepareAssets(response.aiMessage().toolExecutionRequests(), offered, session, history);
            } catch (IOException | RuntimeException error) {
                session.fail("Vue 工程素材准备失败，请重试");
            }
        }

        String execute(ToolSpecification spec, ToolExecutionRequest call) {
            synchronized (session) {
                if (!session.isRunning()) return "任务已结束";
                if (batch.preparing() && Set.of("writeFile", "replaceFragment", "deleteComponent").contains(call.name()))
                    return "本批只准备素材，此文件操作未执行。请根据返回的素材结果重新提交需要的源码操作。";
                try {
                    var args = mapper.readTree(call.arguments());
                    if (args == null || !args.isObject() || spec.parameters() != null
                            && !spec.parameters().required().stream().allMatch(k -> args.path(k).isTextual()))
                        throw new IllegalArgumentException();
                    var result = new DefaultToolExecutor(sourceTools, call).executeWithContext(call, InvocationContext.builder().build());
                    if (result.isError()) {
                        session.fail(SourceTools.NO_MATCH.equals(result.resultText()) ? SourceTools.NO_MATCH : FILE_ERROR);
                        return FILE_ERROR;
                    }
                    report("\n" + sourceTools.progress + "\n");
                    return result.resultText();
                } catch (IOException | RuntimeException invalid) {
                    session.fail(FILE_ERROR);
                    return FILE_ERROR;
                }
            }
        }

        void report(String text) { history.append(text); session.onPartialResponse(text); }
        void error(Throwable error) {
            if (remainingToolRounds <= 0 && String.valueOf(error.getMessage()).contains("maxToolCallingRoundTrips"))
                session.fail(LIMIT_ERROR);
            else if (session.isRunning()) session.onError(error);
        }
    }

    private final class SourceTools {
        private static final String NO_MATCH = "原代码片段未找到或匹配多处，未替换，请重试";
        private String progress;

        @Tool("列出本次 Vue 草稿 src 内已有的源码路径，无需参数")
        public String listFiles() throws IOException {
            progress = "已列出工程文件";
            return mapper.writeValueAsString(files.listVueSources(userId, appId, generationId));
        }

        @Tool("读取 src 源文件或只读照片署名 xy-photo.json")
        public String readFile(@P("相对路径") String relativePath) throws IOException {
            progress = "已读取：" + relativePath;
            if (ArtworkProviders.MERMAID_IMAGE.equals(relativePath)) return "图像由平台生成，节点和连线见 xy-architecture.json";
            return GeneratedFileManager.VUE_PHOTO.equals(relativePath) ? readPhotoAttribution()
                    : files.readVueSource(userId, appId, generationId, relativePath);
        }

        @Tool("写入本次工程 src 中的完整源码，不可修改启动入口 src/main.js")
        public String writeFile(@P("相对路径") String relativePath, @P("完整源码") String content) throws IOException {
            files.writeVueSource(userId, appId, generationId, relativePath, content);
            changedSource = true;
            progress = "已写入：" + relativePath;
            return "文件已保存：" + relativePath;
        }

        @Tool("局部修改源文件，仅替换原文中唯一匹配的片段")
        public String replaceFragment(@P("相对路径") String relativePath, @P("原文片段") String oldText,
                @P("替换后的片段") String newText) throws IOException {
            if (!files.replaceVueSourceFragment(userId, appId, generationId, relativePath, oldText, newText)) {
                throw new IllegalArgumentException(NO_MATCH);
            }
            changedSource = true;
            return progress = "已局部修改：" + relativePath;
        }

        @Tool("删除不再使用的非入口 .vue 组件，须先清理引用")
        public String deleteComponent(@P("相对路径") String relativePath) throws IOException {
            files.deleteVueComponent(userId, appId, generationId, relativePath);
            assetPrompt.remove(relativePath);
            deletedComponents.add(relativePath);
            changedSource = true;
            return progress = "已删除组件：" + relativePath;
        }
    }

    private record AssetBatch(Map<ToolExecutionRequest, String> receipts, boolean preparing) {
        static final AssetBatch EMPTY = new AssetBatch(Map.of(), false);
    }

    private AssetBatch prepareAssets(List<ToolExecutionRequest> calls, List<ToolSpecification> tools,
            GenerationSession session, StringBuilder history) throws IOException {
        var planned = new HashSet<>(calls.stream().map(ToolExecutionRequest::name).filter(ASSET_TOOLS::contains).toList());
        if (planned.isEmpty()) return AssetBatch.EMPTY;
        planned.retainAll(tools.stream().map(ToolSpecification::name).toList());
        // 回执绑定本次调用；等待照片不改变模型提出的顺序，同名工具也不会互相覆盖。
        var receipts = new IdentityHashMap<ToolExecutionRequest, String>();
        var locals = new VueAssetPrompt();
        var photoCall = planned.contains("findPhoto")
                ? calls.stream().filter(call -> call.name().equals("findPhoto")).findFirst().orElseThrow() : null;
        String theme = photoCall == null ? "" : photoTheme(photoCall.arguments());
        var lookup = photoCall == null ? null : new FutureTask<>(() -> photoSearch.find(theme));
        synchronized (session) {
            if (!session.isRunning()) return AssetBatch.EMPTY;
            if (lookup != null) {
                photoRequested = true;
                String progress = planned.size() > 1 ? "\n正在并行准备内容配图和本地素材…\n" : "\n正在查找内容配图…\n";
                history.append(progress);
                session.onPartialResponse(progress);
                if (!session.isRunning()) return AssetBatch.EMPTY;
                pendingPhoto = lookup;
                executor.execute(lookup);
            }
        }
        // 云图像模型调用不占会话锁，等待时照片、心跳和停止仍可推进。
        var remoteLogo = Optional.<String>empty();
        if (planned.contains("generateLogo") && !logoRequested && artwork.rendersLogo() && session.isRunning()) {
            var requested = calls.stream().filter(t -> t.name().equals("generateLogo")).findFirst().orElseThrow();
            remoteLogo = artwork.logoImage(mapper, requested.arguments());
        }
        synchronized (session) {
            for (var call : calls) {
                if (!session.isRunning()) return AssetBatch.EMPTY;
                String result = switch (call.name()) {
                    case "findPhoto" -> "本轮不再搜索照片，请沿用工程已有素材或无图布局。";
                    case "selectIllustration" -> {
                        if (!planned.contains(call.name()) || illustrationRequested) yield "本轮不再选择插画，沿用已有素材或继续编写页面。";
                        illustrationRequested = true;
                        yield selectIllustration(call.arguments(), session, history, locals);
                    }
                    case "drawArchitecture" -> planned.contains(call.name()) && remainingDiagramAttempts > 0
                            ? drawArchitecture(call.arguments(), session, history, locals) : "本轮不再绘制架构图，沿用已有图或用文字说明关系。";
                    case "generateLogo" -> {
                        if (!planned.contains(call.name()) || logoRequested) yield "本轮不再绘制 Logo，请沿用已有标志或品牌文字。";
                        logoRequested = true;
                        yield generateLogo(call.arguments(), remoteLogo, session, history, locals);
                    }
                    default -> null;
                };
                if (result != null) receipts.put(call, result);
            }
        }
        // 等待图库时不占用会话锁，停止和心跳照常处理。
        Optional<OpenversePhotoSearch.Photo> photo = Optional.empty();
        if (lookup != null) {
            try {
                photo = lookup.get();
            } catch (CancellationException cancelled) {
                return AssetBatch.EMPTY;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("素材准备已中断", interrupted);
            } catch (ExecutionException unavailable) {
                // 可选图库不可用，已准备的本地素材仍交给代码模型。
            }
        }
        synchronized (session) {
            if (!session.isRunning()) return AssetBatch.EMPTY;
            if (lookup != null) {
                pendingPhoto = null;
                receipts.put(photoCall, savePhoto(theme, photo, session, history));
            }
            assetPrompt.addAll(locals);
            return new AssetBatch(receipts, !planned.isEmpty());
        }
    }

    private String drawArchitecture(String arguments, GenerationSession session, StringBuilder history, VueAssetPrompt assets) throws IOException {
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
        var component = artwork.rendersMermaid() ? ArtworkProviders.MERMAID_COMPONENT : VueArchitectureDiagram.bundledRenderer();
        if (artwork.rendersMermaid()) files.writeVueSource(userId, appId, generationId, ArtworkProviders.MERMAID_IMAGE, "{}");
        files.writeVueSource(userId, appId, generationId, VueArchitectureDiagram.DATA, mapper.writeValueAsString(diagram));
        files.writeVueSource(userId, appId, generationId, VueArchitectureDiagram.COMPONENT, component);
        files.markVueArchitecture(userId, appId, generationId);
        assets.add(VueArchitectureDiagram.COMPONENT, "架构图", diagram.nodes().size() + " 个节点、"
                + diagram.edges().size() + " 条连线，组件读取 " + VueArchitectureDiagram.DATA + "，用于系统说明");
        remainingDiagramAttempts = 0;
        String progress = "已绘制架构图：" + diagram.nodes().size() + " 个节点、" + diagram.edges().size() + " 条连线。";
        history.append('\n').append(progress).append('\n');
        session.onPartialResponse("\n" + progress + "\n");
        return progress + "请在 src/App.vue 中 import XyArchitecture from './components/XyArchitecture.vue'，"
                + "在页面相应位置使用 <XyArchitecture />。组件及关系数据已保存，无须重写。";
    }

    private String generateLogo(String arguments, Optional<String> raster, GenerationSession session, StringBuilder history, VueAssetPrompt assets) throws IOException {
        var source = raster.map(img -> "<template>" + img + "</template>").or(() -> VueBrandLogo.render(mapper, arguments));
        String progress, result;
        if (source.isPresent()) {
            files.writeVueSource(userId, appId, generationId, VueBrandLogo.COMPONENT, source.get());
            assets.add(VueBrandLogo.COMPONENT, "品牌 Logo", "在导航或品牌区使用该组件");
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

    private String selectIllustration(String arguments, GenerationSession session, StringBuilder history, VueAssetPrompt assets) throws IOException {
        var selected = ThemeIllustrations.select(mapper, arguments);
        String progress, result;
        if (selected.isPresent()) {
            var illustration = selected.get();
            files.writeVueSource(userId, appId, generationId, ThemeIllustrations.VUE_COMPONENT, illustration.vueSource());
            assets.add(ThemeIllustrations.VUE_COMPONENT, "主题插画", illustration.label() + "主题，在对应介绍区域使用该组件");
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

    private String photoTheme(String arguments) {
        try {
            var args = mapper.readTree(arguments);
            return args == null ? "" : args.path("theme").asText("");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ignored) {
            return "";
        }
    }

    private String savePhoto(String theme, Optional<OpenversePhotoSearch.Photo> photo,
            GenerationSession session, StringBuilder history) throws IOException {
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

    private Map<String, String> sourcesForModel() throws IOException {
        var sources = files.readVueSources(userId, appId, generationId);
        sources.computeIfPresent(ArtworkProviders.MERMAID_IMAGE, (k,v) -> "平台按最终节点和连线渲染的图像");
        sources.replaceAll((k,v) -> v.contains("data-xy-artwork=") ? v.replaceAll("data:image/png;base64,[A-Za-z0-9+/=]+", "[本地图像]") : v);
        return sources;
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

    private boolean prepareDelivery(GenerationSession session) throws IOException {
        String error = changedSource ? candidateError() : "本次未写入 Vue 源码，请重试";
        if (error != null) {
            session.fail(error);
            return false;
        }
        if (files.hasVueArchitecture(userId, appId, generationId)) {
            var sources = files.readVueSources(userId, appId, generationId);
            if (VueComponentReferences.references(sources, ArtworkProviders.MERMAID_IMAGE)) {
                var diagram = VueArchitectureDiagram.parse(mapper, sources.get(VueArchitectureDiagram.DATA));
                String src = artwork.diagramImage(diagram);
                synchronized (session) {
                    if (!session.isRunning()) return false;
                    files.writeVueSource(userId, appId, generationId, ArtworkProviders.MERMAID_IMAGE,
                            mapper.writeValueAsString(Map.of("src", src)));
                }
            }
        }
        return session.isRunning();
    }

    private void build(GenerationSession session, StringBuilder history) {
        try {
            if (!prepareDelivery(session)) return;
            synchronized (session) {
                if (!session.isRunning()) return;
                files.finishVueProject(userId, appId, generationId, template);
                buildPaths = files.prepareVueBuild(userId, appId, generationId);
                String progress = "\n源码已保存，正在构建 Vue 工程…\n";
                history.append(progress);
                session.onPartialResponse(progress);
            }
            if (session.isRunning()) builder.build(buildPaths, session::isRunning);
        } catch (IllegalArgumentException error) {
            session.fail(invalidOutputMessage());
        } catch (IOException | RuntimeException error) {
            session.fail("Vue 工程构建失败，请检查源码和构建环境后重试");
        }
    }

    private void deliver(GenerationSession session, StringBuilder history) {
        try {
            if (!session.isRunning() || (!builder.enabled() && !prepareDelivery(session))) return;
            history.append('\n').append(completedMessage());
            session.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.STOP)
                    .aiMessage(AiMessage.from(history.toString())).build());
        } catch (IllegalArgumentException error) {
            session.fail(invalidOutputMessage());
        } catch (IOException | RuntimeException error) {
            session.fail("Vue 工程交付失败，请检查源码后重试");
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
