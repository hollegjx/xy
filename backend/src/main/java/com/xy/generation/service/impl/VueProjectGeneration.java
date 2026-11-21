package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.VueProjectBuildManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

// 一次 Vue 源码生成的工具循环；任务终态和 SSE 生命周期仍由 GenerationSession 管理。
final class VueProjectGeneration implements GenerationSession.OutputHandler {
    private static final Map<String, String> TOOL_LABELS = Map.of(
            "listFiles", "列出工程文件", "readFile", "读取文件", "writeFile", "写入文件",
            "replaceFragment", "替换代码片段", "deleteComponent", "删除组件",
            "findPhoto", "查找照片", "selectIllustration", "选择插画",
            "drawArchitecture", "绘制架构图", "generateLogo", "生成 Logo");
    private static final Set<String> ASSET_TOOLS = Set.of("findPhoto", "selectIllustration", "drawArchitecture", "generateLogo");
    private static final ToolSpecification FIND_PHOTO = ToolSpecification.builder().name("findPhoto")
            .description("首次创建时，为每个需要真实照片的位置选择预设主题；多处用途可同批调用")
            .parameters(JsonObjectSchema.builder().addEnumProperty("theme", OpenversePhotoSearch.themes(), "预设照片主题")
                    .addStringProperty("purpose", "在页面中的用途或位置，仅用于本地素材清单")
                    .required("theme").additionalProperties(false).build()).build();
    private final OpenversePhotoSearch photoSearch;
    private final Executor executor;
    private final ArtworkProviders artwork;
    private WorkflowAssetTasks pendingAssets;
    private final VueDiagramAssets diagrams;
    private final long userId, appId, generationId;
    private final GeneratedFileManager files;
    private final ObjectMapper mapper;
    private final Map<String, String> template;
    private final VueProjectBuildManager builder;
    private final boolean qualityCheck;
    private GenerationQualityWorkflow workflow;
    private GeneratedFileManager.VueBuildPaths buildPaths;
    private boolean choosingAssets;
    private final VuePhotos photos = new VuePhotos();
    private final Map<String, String> illustrationFiles = new LinkedHashMap<>();
    private final VueLogoComponents logos = new VueLogoComponents();
    private int remainingToolRounds;
    private final VueSourceTools sourceTools;
    private boolean changedSource;
    private final Set<String> deletedComponents = new HashSet<>();
    private final VueAssetPrompt assetPrompt = new VueAssetPrompt();

    VueProjectGeneration(long userId, long appId, long generationId, GeneratedFileManager files,
            ObjectMapper mapper, Map<String, String> template, VueProjectBuildManager builder, boolean qualityCheck,
            OpenversePhotoSearch photoSearch, Executor executor, int maxToolRounds, ArtworkProviders artwork) {
        this.remainingToolRounds = maxToolRounds;
        this.artwork = artwork;
        this.diagrams = new VueDiagramAssets(mapper, artwork, files, userId, appId, generationId);
        this.userId = userId;
        this.appId = appId;
        this.generationId = generationId;
        this.files = files;
        this.mapper = mapper;
        this.sourceTools = new VueSourceTools(mapper, new DraftFiles());
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
        if (!choosingAssets) {
            try {
                diagrams.inherit();
            } catch (IOException error) {
                session.onContextError(error);
                return;
            }
        }
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
                    return mapper.writeValueAsString(sources);
                }, done -> generateTurn(model, conversation, session, history, done),
                builder.enabled() ? () -> build(session, history) : null, () -> deliver(session, history), text -> {
                    history.append(text);
                    session.onPartialResponse(text);
                });
        workflow.runWithToolAssets(assetPrompt::manifest);
    }

    @Override
    public void stop() {
        if (pendingAssets != null) pendingAssets.stop();
        if (workflow != null) workflow.stop();
    }

    private interface Writer {
        @dev.langchain4j.service.UserMessage("{{request}}")
        TokenStream write(@dev.langchain4j.service.V("request") String prompt);
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
                        session.fail(VueSourceTools.FILE_ERROR);
                        return ToolExecutionResultMessage.from(call, VueSourceTools.FILE_ERROR);
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
            for (var spec : VueSourceTools.SPECIFICATIONS) registry.put(spec, (call, id) -> execute(call));
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
                var tools = new ArrayList<>(VueSourceTools.SPECIFICATIONS);
                if (choosingAssets && !changedSource) {
                    tools.add(FIND_PHOTO);
                    tools.add(ThemeIllustrations.TOOL);
                    tools.add(VueArchitectureDiagram.TOOL);
                    tools.add(VueBrandLogo.TOOL);
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
            } catch (RuntimeException error) {
                session.fail("Vue 工程素材准备失败，请重试");
            }
        }

        String execute(ToolExecutionRequest call) {
            synchronized (session) {
                if (!session.isRunning()) return "任务已结束";
                if (batch.preparing() && Set.of("writeFile", "replaceFragment", "deleteComponent").contains(call.name()))
                    return "本批只准备素材，此文件操作未执行。请根据返回的素材结果重新提交需要的源码操作。";
                try {
                    var reply = sourceTools.execute(call);
                    report("\n" + reply.progress() + "\n");
                    return reply.receipt();
                } catch (VueSourceTools.Rejected rejected) {
                    session.fail(rejected.getMessage());
                    return VueSourceTools.FILE_ERROR;
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

    private final class DraftFiles implements VueSourceTools.Draft {
        @Override public List<String> list() throws IOException {
            return files.listVueSources(userId, appId, generationId);
        }

        @Override public String read(String relativePath) throws IOException {
            String image = diagrams.imageDescription(relativePath);
            if (image != null) return image;
            return GeneratedFileManager.isVuePhoto(relativePath) ? readPhotoAttribution(relativePath)
                    : files.readVueSource(userId, appId, generationId, relativePath);
        }

        @Override public void write(String relativePath, String content) throws IOException {
            files.writeVueSource(userId, appId, generationId, relativePath, content);
            changedSource = true;
        }

        @Override public boolean replace(String relativePath, String oldText, String newText) throws IOException {
            boolean replaced = files.replaceVueSourceFragment(userId, appId, generationId, relativePath, oldText, newText);
            if (replaced) changedSource = true;
            return replaced;
        }

        @Override public void delete(String relativePath) throws IOException {
            files.deleteVueComponent(userId, appId, generationId, relativePath);
            assetPrompt.remove(relativePath);
            deletedComponents.add(relativePath);
            changedSource = true;
        }
    }

    private record AssetBatch(Map<ToolExecutionRequest, String> receipts, boolean preparing) {
        static final AssetBatch EMPTY = new AssetBatch(Map.of(), false);
    }

    private record AssetResult(ToolExecutionRequest call, String receipt, VueAssetPrompt assets) {}

    private AssetBatch prepareAssets(List<ToolExecutionRequest> calls, List<ToolSpecification> tools,
            GenerationSession session, StringBuilder history) {
        // 在模型提出的顺序中分配各用途的路径；不同品牌和不同种类不必互相等待。
        var groups = new LinkedHashMap<String, List<ToolExecutionRequest>>();
        for (var call : calls) if (ASSET_TOOLS.contains(call.name()))
            groups.computeIfAbsent(call.name(), ignored -> new ArrayList<>()).add(call);
        if (groups.isEmpty()) return AssetBatch.EMPTY;
        var permitted = tools.stream().map(ToolSpecification::name).toList();
        var tasks = new WorkflowAssetTasks(executor);
        synchronized (session) {
            if (!session.isRunning()) return AssetBatch.EMPTY;
            pendingAssets = tasks;
            long count = groups.keySet().stream().filter(permitted::contains).count();
            String progress = count > 1 ? "\n正在并行准备本批素材…\n"
                    : groups.containsKey("findPhoto") && permitted.contains("findPhoto") ? "\n正在查找内容配图…\n" : "";
            if (!progress.isEmpty()) { history.append(progress); session.onPartialResponse(progress); }
        }
        try {
            var jobs = new ArrayList<CompletableFuture<List<AssetResult>>>();
            for (var group : groups.entrySet()) {
                if (group.getKey().equals("findPhoto") && permitted.contains(group.getKey())) {
                    jobs.add(photos.collect(group.getValue(), mapper, photoSearch, tasks, session::isRunning)
                            .thenApply(choices -> savePhotos(choices, session, history)));
                } else if (group.getKey().equals("drawArchitecture") && permitted.contains(group.getKey())) {
                    jobs.add(prepareDiagrams(group.getValue(), tasks, session, history));
                } else if (group.getKey().equals("generateLogo") && permitted.contains(group.getKey())) {
                    jobs.add(prepareLogos(group.getValue(), tasks, session, history));
                } else {
                    jobs.add(CompletableFuture.supplyAsync(() -> group.getValue().stream()
                            .map(call -> prepareAsset(call, permitted.contains(call.name()), session, history)).toList(), tasks));
                }
            }
            tasks.await(jobs);
            synchronized (session) {
                if (!session.isRunning()) return AssetBatch.EMPTY;
                var byCall = new IdentityHashMap<ToolExecutionRequest, AssetResult>();
                for (var job : jobs) for (var result : job.join()) byCall.put(result.call(), result);
                var receipts = new IdentityHashMap<ToolExecutionRequest, String>();
                for (var call : calls) {
                    var result = byCall.get(call);
                    if (result == null) continue;
                    receipts.put(call, result.receipt());
                    assetPrompt.addAll(result.assets());
                }
                return new AssetBatch(receipts, groups.keySet().stream().anyMatch(permitted::contains));
            }
        } catch (RuntimeException failure) {
            tasks.stop();
            throw failure;
        } finally {
            synchronized (session) { pendingAssets = null; }
        }
    }

    private AssetResult prepareAsset(ToolExecutionRequest call, boolean permitted, GenerationSession session, StringBuilder history) {
        var assets = new VueAssetPrompt();
        if (!session.isRunning()) return new AssetResult(call, "任务已结束", assets);
        try {
            String receipt = switch (call.name()) {
                case "findPhoto" -> "本轮不再搜索照片，请沿用工程已有素材或无图布局。";
                case "selectIllustration" -> {
                    if (!permitted) yield "本轮不再选择插画，沿用已有素材或继续编写页面。";
                    synchronized (session) {
                        yield session.isRunning() ? selectIllustration(call.arguments(), session, history, assets) : "任务已结束";
                    }
                }
                case "drawArchitecture" -> "本轮不再绘制架构图，沿用已有图或用文字说明关系。";
                case "generateLogo" -> "本轮不再绘制 Logo，请沿用已有标志或品牌文字。";
                default -> throw new IllegalArgumentException("未知素材类型");
            };
            return new AssetResult(call, receipt, assets);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private CompletableFuture<List<AssetResult>> prepareDiagrams(List<ToolExecutionRequest> calls, WorkflowAssetTasks tasks,
            GenerationSession session, StringBuilder history) {
        var uses = calls.stream().map(call -> diagrams.prepare(call.arguments(), tasks)).toList();
        return CompletableFuture.allOf(uses.stream().map(VueDiagramAssets.Use::ready).toArray(CompletableFuture<?>[]::new))
                .thenApply(done -> {
                    synchronized (session) {
                        if (!session.isRunning()) return List.of();
                        var results = new ArrayList<AssetResult>();
                        for (int i = 0; i < uses.size(); i++) {
                            if (!session.isRunning()) return List.of();
                            var assets = new VueAssetPrompt();
                            try {
                                String receipt = diagrams.save(uses.get(i), assets, notice -> {
                                    history.append('\n').append(notice).append('\n');
                                    session.onPartialResponse("\n" + notice + "\n");
                                });
                                results.add(new AssetResult(calls.get(i), receipt, assets));
                            } catch (IOException error) { throw new UncheckedIOException(error); }
                        }
                        return results;
                    }
                });
    }

    private CompletableFuture<List<AssetResult>> prepareLogos(List<ToolExecutionRequest> calls, WorkflowAssetTasks tasks,
            GenerationSession session, StringBuilder history) {
        var uses = calls.stream().map(call -> logos.prepare(call.arguments(), mapper, artwork, tasks)).toList();
        var ready = uses.stream().map(use -> use.drawing().source()).toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(ready).thenApply(unused -> {
            var results = new ArrayList<AssetResult>();
            synchronized (session) {
                if (!session.isRunning()) return List.of();
                for (int i = 0; i < uses.size(); i++) {
                    var use = uses.get(i);
                    var drawing = use.drawing();
                    var source = drawing.source().join();
                    var assets = new VueAssetPrompt();
                    String progress, receipt;
                    if (source.isPresent()) {
                        if (logos.firstWrite(drawing.path())) {
                            try { files.writeVueSource(userId, appId, generationId, drawing.path(), source.get()); }
                            catch (IOException error) { throw new UncheckedIOException(error); }
                            progress = "已生成品牌 Logo：" + drawing.brand() + "。";
                        } else progress = "已复用品牌 Logo：" + drawing.brand() + "。";
                        assets.add(drawing.path(), "品牌 Logo", drawing.brand() + "；用途："
                                + (use.purpose().isBlank() ? "品牌区" : use.purpose()));
                        receipt = progress + "从 src/App.vue 导入 './" + drawing.path().substring("src/".length()) + "'。";
                    } else {
                        progress = "Logo 图形无效，改用品牌文字。";
                        receipt = progress + "这个位置没有可引用的 Logo 组件。";
                    }
                    history.append('\n').append(progress).append('\n');
                    session.onPartialResponse("\n" + progress + "\n");
                    results.add(new AssetResult(calls.get(i), receipt, assets));
                }
            }
            return results;
        });
    }

    private String selectIllustration(String arguments, GenerationSession session, StringBuilder history, VueAssetPrompt assets) throws IOException {
        var selected = ThemeIllustrations.select(mapper, arguments);
        String progress, result;
        if (selected.isPresent()) {
            var selection = selected.get();
            var illustration = selection.illustration();
            String component = illustrationFiles.get(selection.theme());
            if (component == null) {
                component = illustrationFiles.isEmpty() ? ThemeIllustrations.VUE_COMPONENT
                        : "src/components/XyIllustration" + (illustrationFiles.size() + 1) + ".vue";
                files.writeVueSource(userId, appId, generationId, component, illustration.vueSource());
                illustrationFiles.put(selection.theme(), component);
            }
            assets.add(component, "主题插画", illustration.label() + "主题；"
                    + (selection.purpose().isBlank() ? "在对应介绍区域使用" : "用途：" + selection.purpose()));
            progress = "已准备插画：" + illustration.label();
            result = progress + "。从 src/App.vue 导入 './" + component.substring("src/".length()) + "' 并用于相应位置；组件已保存，无须重写。";
        } else {
            progress = "没有匹配的内置插画，继续生成。";
            result = progress + "不要引用未提供的插画组件。";
        }
        history.append('\n').append(progress).append('\n');
        session.onPartialResponse("\n" + progress + "\n");
        return result;
    }

    private List<AssetResult> savePhotos(List<VuePhotos.Choice> choices, GenerationSession session, StringBuilder history) {
        synchronized (session) {
            if (!session.isRunning()) return List.of();
            var results = new ArrayList<AssetResult>();
            for (var choice : choices) {
                var use = choice.use();
                var photo = choice.photo();
                var assets = new VueAssetPrompt();
                String progress, receipt;
                if (photo != null) {
                    try {
                        files.saveVuePhoto(userId, appId, generationId, use.path(), use.file(photo, mapper));
                        receipt = "已保存内容配图：" + use.path() + "。从 src/App.vue 导入 '../" + use.path()
                                + "'，绑定 photo.src 展示图片。来源和许可：" + mapper.writeValueAsString(use.credit(photo));
                    } catch (IOException error) { throw new UncheckedIOException(error); }
                    assets.add(use.path(), "内容照片", use.theme() + " 主题；用途：" + (use.purpose().isBlank() ? "内容配图" : use.purpose())
                            + "；绑定 photo.src 显示，许可 " + photo.license() + "，署名使用 photo.source");
                    progress = "已准备本地内容配图";
                } else {
                    progress = "未取得可用配图";
                    receipt = "这个位置未取得可用配图，请使用无图布局；其他位置沿用各自返回的文件。";
                }
                String notice = "\n" + progress + (use.purpose().isBlank() ? "。" : "：" + use.purpose() + "。") + "\n";
                history.append(notice);
                session.onPartialResponse(notice);
                results.add(new AssetResult(use.call(), receipt, assets));
            }
            return results;
        }
    }

    private Map<String, String> sourcesForModel() throws IOException {
        var sources = files.readVueSources(userId, appId, generationId);
        sources.replaceAll((path, source) -> java.util.Objects.requireNonNullElse(diagrams.imageDescription(path), source));
        sources.replaceAll((k,v) -> v.contains("data-xy-artwork=") ? v.replaceAll("data:image/png;base64,[A-Za-z0-9+/=]+", "[本地图像]") : v);
        for (var name : files.listVuePhotos(userId, appId, generationId)) sources.put(name, readPhotoAttribution(name));
        return sources;
    }

    private String readPhotoAttribution(String name) throws IOException {
        var attribution = mapper.readValue(files.readVuePhoto(userId, appId, generationId, name), VuePhotos.Credit.class);
        return mapper.writeValueAsString(attribution);
    }

    private boolean prepareDelivery(GenerationSession session) throws IOException {
        if (!changedSource) {
            session.fail("本次未写入 Vue 源码，请重试");
            return false;
        }
        Map<String, String> sources;
        try {
            sources = files.readVueSources(userId, appId, generationId);
        } catch (NoSuchFileException missing) {
            session.fail(invalidOutputMessage());
            return false;
        }
        String error = VueComponentReferences.missingPhotoError(sources, Set.copyOf(files.listVuePhotos(userId, appId, generationId)));
        if (error == null) error = diagrams.findError(sources);
        if (error == null) error = logos.missingReference(sources);
        if (error == null && !deletedComponents.isEmpty()) error = VueComponentReferences.findError(sources, deletedComponents);
        if (error != null) {
            session.fail(error);
            return false;
        }
        return diagrams.refreshImages(sources, session);
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
