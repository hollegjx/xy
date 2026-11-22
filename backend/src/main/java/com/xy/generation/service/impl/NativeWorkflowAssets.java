package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.generation.service.impl.WorkflowAssetPlan.Themed;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 首次原生工作流的素材只保留在本轮。图状态和模型上下文仅传计划、标记和署名。 */
final class NativeWorkflowAssets {
    private record VectorSlot(String attribute, String marker) {}

    private final ObjectMapper mapper;
    private final NativeContentPhoto photo;
    private final NativeAssetImages images;
    private final WorkflowAssetTasks tasks;
    private final Map<String, ThemeIllustrations.Illustration> illustrations = new HashMap<>();
    private final Map<VectorSlot, String> vectors = new HashMap<>();

    NativeWorkflowAssets(ObjectMapper mapper, NativeContentPhoto photo, NativeAssetImages images, Executor executor) {
        this.mapper = mapper;
        this.photo = photo;
        this.images = images;
        this.tasks = new WorkflowAssetTasks(executor);
    }

    String collect(WorkflowAssetPlan plan, GenerationSession session) {
        if (!session.isRunning()) return "";
        var batch = new Batch();
        collectPhotos(plan.photos(), session, batch);
        collectIllustrations(plan.illustrations(), batch);
        collectVectors(plan, batch);
        return batch.finish(session);
    }

    private void collectPhotos(List<Themed> requested, GenerationSession session, Batch batch) {
        var photos = requested.stream()
                .filter(r -> r.theme() != null && OpenversePhotoSearch.themes().contains(r.theme())).toList();
        var counts = photos.stream().collect(Collectors.groupingBy(Themed::theme, Collectors.summingInt(r -> 1)));
        var positions = new HashMap<String, Integer>();
        var searches = new HashMap<String, CompletableFuture<List<OpenversePhotoSearch.Photo>>>();
        for (var request : photos) {
            String theme = request.theme();
            var found = searches.computeIfAbsent(theme, key -> CompletableFuture.supplyAsync(
                    () -> photo.collect(key, counts.get(key), session), tasks));
            int index = positions.merge(theme, 1, Integer::sum) - 1;
            String marker = index == 0 ? theme : theme + "-" + (index + 1);
            batch.use(found, results -> {
                var image = index < results.size() ? results.get(index) : null;
                photo.remember(marker, image);
                if (image != null) batch.add("内容照片：<img data-xy-photo=\"" + marker
                        + "\" alt=\"填写准确的替代文字\">；不用写 src。许可 " + image.license() + "，来源 " + image.source(), request.purpose());
            });
        }
    }

    private void collectIllustrations(List<Themed> requested, Batch batch) {
        for (var r : requested) {
            if (r.theme() == null) continue;
            batch.use(prepare(() -> ThemeIllustrations.load(r.theme())), art -> art.ifPresent(a -> {
                illustrations.put(r.theme(), a);
                batch.add("主题插画：" + a.label() + "，在正文放置 <!--xy:illustration:" + r.theme() + "-->", r.purpose());
            }));
        }
    }

    private void collectVectors(WorkflowAssetPlan plan, Batch batch) {
        var logos = plan.logos();
        for (int i = 0; i < logos.size(); i++) {
            var task = logos.get(i);
            if (task.design() == null) continue;
            var slot = slot("data-xy-logo", i);
            var result = prepare(() -> images.logo(mapper, mapper.writeValueAsString(task.design())));
            batch.use(result, ready -> ready.ifPresent(markup -> {
                vectors.put(slot, markup);
                batch.add("品牌 Logo：" + task.design().name() + "，放置 <span data-xy-logo=\"" + slot.marker()
                        + "\">品牌文字</span>", task.purpose());
            }));
        }
        var diagrams = plan.architectures();
        var drawings = new HashMap<VueArchitectureDiagram.Diagram, CompletableFuture<Optional<String>>>();
        for (int i = 0; i < diagrams.size(); i++) {
            var task = diagrams.get(i);
            if (task.design() == null) continue;
            var slot = slot("data-xy-architecture", i);
            var result = drawings.computeIfAbsent(task.design(), design -> prepare(() -> Optional.of(images.diagram(
                    VueArchitectureDiagram.parse(mapper, mapper.writeValueAsString(design))))));
            batch.use(result, ready -> ready.ifPresent(markup -> {
                vectors.put(slot, markup);
                batch.add("架构图：" + String.join("、", task.design().nodes()) + "，放置 <figure data-xy-architecture=\""
                        + slot.marker() + "\"><figcaption>关系说明</figcaption></figure>", task.purpose());
            }));
        }
    }

    private static VectorSlot slot(String attribute, int index) {
        return new VectorSlot(attribute, index == 0 ? "prepared" : "prepared-" + (index + 1));
    }

    // 供应方各自运行，清单与交付用的标记只在全部结束后按计划顺序登记。
    private final class Batch {
        final List<CompletableFuture<?>> pending = new ArrayList<>();
        final List<Runnable> register = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        <T> void use(CompletableFuture<T> result, Consumer<T> store) {
            pending.add(result);
            register.add(() -> store.accept(result.join()));
        }
        void add(String hint, String purpose) {
            hints.add(purpose == null || purpose.isBlank() ? hint : hint + "；用途：" + purpose);
        }
        String finish(GenerationSession session) {
            tasks.await(pending);
            if (!session.isRunning()) return "";
            register.forEach(Runnable::run);
            return "本轮已准备的页面素材（仅使用列出的素材；没有时采用文字布局）：\n" + (hints.isEmpty() ? "无" : String.join("\n", hints));
        }
    }

    private <T> CompletableFuture<Optional<T>> prepare(Callable<Optional<T>> collect) {
        return CompletableFuture.supplyAsync(() -> {
            try { return collect.call(); }
            catch (Exception unavailable) { return Optional.empty(); }
        }, tasks);
    }

    Map<String, ThemeIllustrations.Illustration> illustrations() { return illustrations; }
    Optional<String> vector(String attribute, String marker) {
        return Optional.ofNullable(vectors.get(new VectorSlot(attribute, marker)));
    }
    void stop() { tasks.stop(); }
}
