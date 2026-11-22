package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.generation.service.impl.VueArchitectureDiagram.Diagram;
import com.xy.generation.service.impl.VueArchitectureDiagram.Slot;
import com.xy.manager.GeneratedFileManager;
import com.xy.manager.GeneratedFileManager.DiagramRenderer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** 按图的实际关系复用绘制结果；每张图保留独立的工程文件和版本来源。 */
final class VueDiagramAssets {
    static final ToolSpecification TOOL = VueAssetTool.describe(Draw.class);
    record Drawing(Slot slot, Diagram diagram, CompletableFuture<String> image) {}
    record Use(String purpose, Drawing drawing, String error) {
        CompletableFuture<?> ready() { return drawing == null ? CompletableFuture.completedFuture(null) : drawing.image(); }
    }
    private record Retry(Slot slot, int attempts) {}
    private record Task(String purpose, JsonNode unnamedNodes) {}
    private record Rendered(Diagram diagram, String image) {}
    private final Map<Diagram, Drawing> drawings = new HashMap<>();
    private final Map<Task, Retry> retries = new HashMap<>();
    private final Set<Slot> saved = new LinkedHashSet<>();
    private final Set<Slot> platformImages = new LinkedHashSet<>();
    private final Set<Slot> unresolved = new LinkedHashSet<>();
    private final Map<Slot, Rendered> rendered = new HashMap<>();
    private int next = 1;
    private final ObjectMapper mapper;
    private final ArtworkProviders artwork;
    private final GeneratedFileManager files;
    private final long user, app, version;

    VueDiagramAssets(ObjectMapper mapper, ArtworkProviders artwork, GeneratedFileManager files, long user, long app, long version) {
        this.mapper = mapper; this.artwork = artwork; this.files = files;
        this.user = user; this.app = app; this.version = version;
    }

    Use prepare(String arguments, Executor workers) {
        return prepare(ToolExecutionRequest.builder().name(TOOL.name()).arguments(arguments).build(), workers, true);
    }

    Use prepare(ToolExecutionRequest call, Executor workers, boolean canRetry) {
        String purpose = "";
        Task task = new Task("", null);
        try {
            JsonNode input = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.arguments());
            if (input != null) purpose = input.path("purpose").asText("").strip();
            task = new Task(purpose, purpose.isEmpty() && input != null ? input.get("nodes") : null);
            // 先检查原始节点及连线：SDK 的类型转换不能把错误端点或数值标签变成合法关系。
            VueArchitectureDiagram.parse(input);
            return VueAssetTool.call(new Draw(task, workers), call, Use.class)
                    .orElseThrow(() -> new IllegalArgumentException("需要合法的节点和连线参数"));
        } catch (IOException | IllegalArgumentException invalid) {
            String why = invalid instanceof IllegalArgumentException ? invalid.getMessage() : "需要节点和连线的 JSON 对象";
            if (!canRetry) return new Use(purpose, null, "架构图关系无效，改用文字说明。" + why + "。");
            var previous = retries.get(task);
            var retry = previous == null ? new Retry(new Slot(next++), 1) : new Retry(previous.slot(), 2);
            retries.put(task, retry);
            return new Use(purpose, null, (retry.attempts() == 1 ? "架构图关系无效，可修正一次。" : "架构图关系仍无效，改用文字说明。") + why + "。");
        }
    }

    private final class Draw {
        private final Task task;
        private final Executor workers;

        Draw(Task task, Executor workers) { this.task = task; this.workers = workers; }

        @Tool("写页面前为每处系统架构或流程图提供节点和有向关系，可同批或分次请求多张图。")
        public Use drawArchitecture(@P("按阅读顺序列出不重复的模块名") List<String> nodes,
                @P("有向关系，from 和 to 均使用 nodes 中的名称") List<VueArchitectureDiagram.Edge> edges,
                @P(value = "本图在页面中的用途或位置；修正无效关系时沿用原用途", required = false) String purpose) {
            String use = purpose == null ? "" : purpose.strip();
            var retry = retries.get(task);
            if (retry != null && retry.attempts() == 2) return new Use(use, null, "该用途的架构图已改用文字说明。");
            retries.remove(task);
            var drawing = drawings.computeIfAbsent(new Diagram(List.copyOf(nodes), List.copyOf(edges)), key -> {
                var slot = retry == null ? new Slot(next++) : retry.slot();
                var image = artwork.rendersMermaid() ? CompletableFuture.supplyAsync(() -> artwork.diagramImage(key), workers)
                        : CompletableFuture.<String>completedFuture(null);
                return new Drawing(slot, key, image);
            });
            return new Use(use, drawing, null);
        }
    }

    String save(Use use, VueAssetPrompt manifest, Consumer<String> report) throws IOException {
        if (use.error() != null) {
            report.accept(use.error());
            return use.error() + "未生成文件，不要引用不存在的组件。";
        }
        var drawing = use.drawing();
        var slot = drawing.slot();
        var diagram = drawing.diagram();
        String counts = diagram.nodes().size() + " 个节点、" + diagram.edges().size() + " 条连线";
        boolean first = saved.add(slot);
        if (first) {
            String image = drawing.image().join();
            if (image != null) {
                platformImages.add(slot);
                files.writeVueSource(user, app, version, slot.image(), imageJson(image));
                rendered.put(slot, new Rendered(diagram, image));
            }
            files.writeVueSource(user, app, version, slot.data(), mapper.writeValueAsString(diagram));
            files.writeVueSource(user, app, version, slot.component(), slot.renderer(image != null));
            files.markVueArchitecture(user, app, version, slot.number(), image == null ? DiagramRenderer.LOCAL : DiagramRenderer.IMAGE);
        }
        manifest.add(slot.component(), "架构图", counts + "，组件读取 " + slot.data() + "；用途："
                + (use.purpose().isBlank() ? "系统说明" : use.purpose()));
        String notice = (first ? "已绘制架构图：" : "已复用架构图：") + counts + "。";
        report.accept(notice);
        return notice + "从 src/App.vue 导入 './" + slot.component().substring(4) + "'，组件及关系数据已保存。";
    }

    void inherit() throws IOException {
        var origins = files.vueArchitectureOrigins(user, app, version);
        if (origins.isEmpty()) return;
        var sources = files.readVueSources(user, app, version);
        for (var origin : origins.entrySet()) {
            var slot = new Slot(origin.getKey());
            saved.add(slot);
            var kind = origin.getValue();
            if (kind == DiagramRenderer.LEGACY) {
                kind = legacyRenderer(sources, slot);
                if (kind == DiagramRenderer.LEGACY) unresolved.add(slot);
                else files.resolveVueArchitectureOrigin(user, app, version, slot.number(), kind);
            }
            if (kind != DiagramRenderer.IMAGE) continue;
            platformImages.add(slot);
            String image = sources.get(slot.image());
            if (image == null) continue;
            try {
                var data = mapper.readTree(image);
                String src = data.path("src").textValue();
                if (src == null || src.isBlank()) continue;
                // 上版未使用的图像可能早于当前关系文件，重新启用时必须按最终关系绘制。
                if (VueComponentReferences.references(sources, slot.image()))
                    rendered.put(slot, new Rendered(VueArchitectureDiagram.parse(mapper, sources.get(slot.data())), src));
            } catch (IOException | IllegalArgumentException invalid) {
                // 未配对的旧数据不能作为成图复用；交付时按最终关系重新校验和绘制。
            }
        }
    }

    private static DiagramRenderer legacyRenderer(Map<String, String> sources, Slot slot) {
        if (VueArchitectureDiagram.containsLegacyRenderer(sources, slot)) return DiagramRenderer.LOCAL;
        if (VueArchitectureDiagram.containsLegacyImageRenderer(sources, slot)) return DiagramRenderer.IMAGE;
        return DiagramRenderer.LEGACY;
    }

    String imageDescription(String path) {
        for (var slot : platformImages) if (slot.image().equals(path)) return "图像由平台生成，节点和连线见 " + slot.data();
        return null;
    }

    String findError(Map<String, String> sources) { return VueArchitectureDiagram.findError(mapper, sources, saved, platformImages); }

    boolean refreshImages(Map<String, String> sources, GenerationSession session) throws IOException {
        // 上版可能已经移除旧组件。本轮恢复了可识别的图片组件才接管，并按最终关系画一张新图。
        for (var it = unresolved.iterator(); it.hasNext(); ) {
            var slot = it.next();
            var kind = legacyRenderer(sources, slot);
            synchronized (session) {
                if (!session.isRunning()) return false;
                if (kind == DiagramRenderer.LEGACY) continue;
                files.resolveVueArchitectureOrigin(user, app, version, slot.number(), kind);
                if (kind == DiagramRenderer.IMAGE) platformImages.add(slot);
                it.remove();
            }
        }
        for (var slot : platformImages) {
            if (!session.isRunning()) return false;
            if (!VueComponentReferences.references(sources, slot.image())) continue;
            var diagram = VueArchitectureDiagram.parse(mapper, sources.get(slot.data()));
            var previous = rendered.get(slot);
            String image = previous != null && previous.diagram().equals(diagram) ? previous.image() : artwork.diagramImage(diagram);
            String json = imageJson(image);
            synchronized (session) {
                if (!session.isRunning()) return false;
                if (!json.equals(sources.get(slot.image()))) files.writeVueSource(user, app, version, slot.image(), json);
            }
        }
        return session.isRunning();
    }

    private String imageJson(String image) throws IOException { return mapper.writeValueAsString(Map.of("src", image)); }
}
