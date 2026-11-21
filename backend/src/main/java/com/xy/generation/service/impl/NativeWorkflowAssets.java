package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 首次原生工作流的素材只保留在本轮。图状态和模型上下文仅传计划、标记和署名。 */
final class NativeWorkflowAssets {
    record Layer(String shape, String color, List<Integer> coordinates) {}
    record Logo(String name, String brief, List<Layer> layers) {}
    record Themed(String theme, String purpose) {}
    record Plan(List<Themed> photos, List<Themed> illustrations, Logo logo, VueArchitectureDiagram.Diagram architecture) {}

    interface Planner {
        @SystemMessage("""
                你是 XY 页面素材规划师。按用户需求选素材，不写页面代码。photos、illustrations 分别是任务列表，不需要时填 []。
                每张图片各占一项，purpose 描述该图在页面的用途或位置。需要同主题的不同照片，可为每张分别列一项。
                照片 theme 只能是公开主题：{{themes}}。不要在主题里填写姓名、地址或自由搜索词。
                插画 theme 只可选 reading（学习阅读）、teamwork（团队协作）、analytics（数据分析）。
                需要品牌 Logo 才填 logo：name 为品牌名称，brief 仅写图形意象，不含用户原文或个人信息。
                layers 用六位十六进制色，坐标 0–100：circle 是 [圆心x,圆心y,半径]，line 和 polygon 分别是至少两对、三对 x,y。
                需要说明系统关系才填 architecture：nodes 为不重复的模块名，edges 的 from、to 必须是其中的名称，label 可省略。
                """)
        @dev.langchain4j.service.UserMessage("{{prompt}}")
        CompletableFuture<Result<Plan>> plan(@V("prompt") String prompt, @V("themes") String themes);
    }

    private final ObjectMapper mapper;
    private final NativeContentPhoto photo;
    private final NativeAssetImages images;
    private final WorkflowAssetTasks tasks;
    private final Map<String, ThemeIllustrations.Illustration> illustrations = new HashMap<>();
    private final Map<String, String> vectors = new HashMap<>();

    NativeWorkflowAssets(ObjectMapper mapper, NativeContentPhoto photo, NativeAssetImages images, Executor executor) {
        this.mapper = mapper;
        this.photo = photo;
        this.images = images;
        this.tasks = new WorkflowAssetTasks(executor);
    }

    CompletableFuture<String> plan(StreamingChatModel model, GenerationSession session, CompletableFuture<?> node, String prompt) {
        session.onPartialResponse("\n正在规划页面素材…\n");
        var adapter = WorkflowNodeModel.adapt(model, session, node, Set.of(), ignored -> {});
        return AiServices.builder(Planner.class).chatModel(adapter).build()
                .plan(prompt, String.join("、", OpenversePhotoSearch.themes())).thenApply(result -> {
                    if (result.finishReason() != FinishReason.STOP || result.content() == null)
                        throw new IllegalArgumentException("素材计划不完整");
                    return mapper.valueToTree(result.content()).toString();
                });
    }

    String collect(String json, GenerationSession session) throws IOException {
        if (!session.isRunning()) return "";
        var plan = mapper.readValue(json, Plan.class);
        var batch = new Batch();
        collectPhotos(plan.photos(), session, batch);
        collectIllustrations(plan.illustrations(), batch);
        collectVectors(plan, batch);
        return batch.finish(session);
    }

    private void collectPhotos(List<Themed> requested, GenerationSession session, Batch batch) {
        var photos = entries(requested).stream()
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
        for (var r : entries(requested)) {
            if (r.theme() == null) continue;
            batch.use(prepare(() -> ThemeIllustrations.load(r.theme())), art -> art.ifPresent(a -> {
                illustrations.put(r.theme(), a);
                batch.add("主题插画：" + a.label() + "，在正文放置 <!--xy:illustration:" + r.theme() + "-->", r.purpose());
            }));
        }
    }

    private void collectVectors(Plan plan, Batch batch) {
        if (plan.logo() != null) batch.use(prepare(() -> images.logo(mapper, mapper.writeValueAsString(plan.logo()))), logo -> logo.ifPresent(markup -> {
            vectors.put("data-xy-logo", markup);
            batch.add("品牌 Logo：" + plan.logo().name() + "，放置 <span data-xy-logo=\"prepared\">品牌文字</span>", null);
        }));
        if (plan.architecture() != null) batch.use(prepare(() -> Optional.of(images.diagram(
                VueArchitectureDiagram.parse(mapper, mapper.writeValueAsString(plan.architecture()))))), diagram -> diagram.ifPresent(markup -> {
            vectors.put("data-xy-architecture", markup);
            batch.add("架构图：" + String.join("、", plan.architecture().nodes())
                    + "，放置 <figure data-xy-architecture=\"prepared\"><figcaption>关系说明</figcaption></figure>", null);
        }));
    }

    private static <T> List<T> entries(List<T> items) {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
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

    static void enhance(List<ChatMessage> conversation, String manifest) {
        var user = (UserMessage) conversation.getLast();
        conversation.set(conversation.size() - 1, UserMessage.from(user.singleText() + "\n\n" + manifest));
    }

    Map<String, ThemeIllustrations.Illustration> illustrations() { return illustrations; }
    Optional<String> vector(String attribute, String marker) {
        return "prepared".equals(marker) ? Optional.ofNullable(vectors.get(attribute)) : Optional.empty();
    }
    void stop() { tasks.stop(); }
}
