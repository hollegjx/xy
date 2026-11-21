package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.generation.service.impl.VueArchitectureDiagram.Diagram;
import com.xy.generation.service.impl.VueArchitectureDiagram.Slot;
import com.xy.manager.GeneratedFileManager;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** 按图的实际关系复用绘制结果；每张图保留独立的工程文件和版本来源。 */
final class VueDiagramAssets {
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
        String purpose = "";
        Task task = new Task("", null);
        Diagram diagram;
        try {
            JsonNode input = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
            if (input != null) purpose = input.path("purpose").asText("").strip();
            task = new Task(purpose, purpose.isEmpty() && input != null ? input.get("nodes") : null);
            diagram = VueArchitectureDiagram.parse(input);
        } catch (IOException | IllegalArgumentException invalid) {
            var previous = retries.get(task);
            var retry = previous == null ? new Retry(new Slot(next++), 1) : new Retry(previous.slot(), 2);
            retries.put(task, retry);
            String why = invalid instanceof IllegalArgumentException ? invalid.getMessage() : "需要节点和连线的 JSON 对象";
            return new Use(purpose, null, (retry.attempts() == 1 ? "架构图关系无效，可修正一次。" : "架构图关系仍无效，改用文字说明。") + why + "。");
        }
        var retry = retries.get(task);
        if (retry != null && retry.attempts() == 2) return new Use(purpose, null, "该用途的架构图已改用文字说明。");
        retries.remove(task);
        var drawing = drawings.computeIfAbsent(diagram, key -> {
            var slot = retry == null ? new Slot(next++) : retry.slot();
            var image = artwork.rendersMermaid() ? CompletableFuture.supplyAsync(() -> artwork.diagramImage(key), workers)
                    : CompletableFuture.<String>completedFuture(null);
            return new Drawing(slot, key, image);
        });
        return new Use(purpose, drawing, null);
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
                files.writeVueSource(user, app, version, slot.image(), imageJson(image));
                rendered.put(slot, new Rendered(diagram, image));
            }
            files.writeVueSource(user, app, version, slot.data(), mapper.writeValueAsString(diagram));
            files.writeVueSource(user, app, version, slot.component(), slot.renderer(image != null));
            files.markVueArchitecture(user, app, version, slot.number());
        }
        manifest.add(slot.component(), "架构图", counts + "，组件读取 " + slot.data() + "；用途："
                + (use.purpose().isBlank() ? "系统说明" : use.purpose()));
        String notice = (first ? "已绘制架构图：" : "已复用架构图：") + counts + "。";
        report.accept(notice);
        return notice + "从 src/App.vue 导入 './" + slot.component().substring(4) + "'，组件及关系数据已保存。";
    }

    void inherit() throws IOException {
        for (int number : files.vueArchitectureSlots(user, app, version)) saved.add(new Slot(number));
        if (saved.isEmpty()) return;
        var sources = files.readVueSources(user, app, version);
        for (var slot : saved) {
            String image = sources.get(slot.image());
            // 上版未使用的图像可能早于当前关系文件，重新启用时必须按最终关系绘制。
            if (image == null || !VueComponentReferences.references(sources, slot.image())) continue;
            try {
                var src = mapper.readTree(image).path("src");
                if (src.isTextual() && !src.textValue().isBlank())
                    rendered.put(slot, new Rendered(VueArchitectureDiagram.parse(mapper, sources.get(slot.data())), src.textValue()));
            } catch (IOException | IllegalArgumentException invalid) {
                // 未配对的旧数据不能作为成图复用；交付时按最终关系重新校验和绘制。
            }
        }
    }

    String imageDescription(String path) {
        for (var slot : saved) if (slot.image().equals(path)) return "图像由平台生成，节点和连线见 " + slot.data();
        return null;
    }

    String findError(Map<String, String> sources) { return VueArchitectureDiagram.findError(mapper, sources, saved); }

    boolean refreshImages(Map<String, String> sources, GenerationSession session) throws IOException {
        for (var slot : saved) {
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
