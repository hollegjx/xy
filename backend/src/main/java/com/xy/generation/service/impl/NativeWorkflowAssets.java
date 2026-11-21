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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 首次原生工作流的素材只保留在本轮。图状态和模型上下文仅传计划、标记和署名。 */
final class NativeWorkflowAssets {
    record Layer(String shape, String color, List<Integer> coordinates) {}
    record Logo(String name, String brief, List<Layer> layers) {}
    record Plan(String photoTheme, String illustrationTheme, Logo logo, VueArchitectureDiagram.Diagram architecture) {}

    interface Planner {
        @SystemMessage("""
                你是 XY 页面素材规划师。按用户需求选素材，不写页面代码。适量选用；明确不要图片时所有项填 null。
                photoTheme 只能是以下公开主题之一或 null：{{themes}}。不要在主题里填写姓名、地址或自由搜索词。
                illustrationTheme 只可选 reading（学习阅读）、teamwork（团队协作）、analytics（数据分析），否则填 null。
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
    private Map<String, ThemeIllustrations.Illustration> illustrations = Map.of();
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
        var credit = prepare(plan.photoTheme() != null && OpenversePhotoSearch.themes().contains(plan.photoTheme()),
                () -> Optional.ofNullable(photo.collect(plan.photoTheme(), session)));
        var art = prepare(plan.illustrationTheme() != null, () -> ThemeIllustrations.load(plan.illustrationTheme()));
        var logo = prepare(plan.logo() != null, () -> images.logo(mapper, mapper.writeValueAsString(plan.logo())));
        var diagram = prepare(plan.architecture() != null, () -> Optional.of(images.diagram(
                VueArchitectureDiagram.parse(mapper, mapper.writeValueAsString(plan.architecture())))));
        tasks.await(List.of(credit, art, logo, diagram));
        if (!session.isRunning()) return "";
        var items = new ArrayList<String>();
        credit.join().ifPresent(c -> items.add("内容照片：<img data-xy-photo=\"" + plan.photoTheme()
                + "\" alt=\"填写准确的替代文字\">；不用写 src。许可 " + c.license() + "，来源 " + c.source()));
        art.join().ifPresent(a -> {
            illustrations = Map.of(plan.illustrationTheme(), a);
            items.add("主题插画：" + a.label() + "，在正文放置 <!--xy:illustration:" + plan.illustrationTheme() + "-->");
        });
        logo.join().ifPresent(markup -> {
            vectors.put("data-xy-logo", markup);
            items.add("品牌 Logo：" + plan.logo().name() + "，放置 <span data-xy-logo=\"prepared\">品牌文字</span>");
        });
        diagram.join().ifPresent(markup -> {
            vectors.put("data-xy-architecture", markup);
            items.add("架构图：" + String.join("、", plan.architecture().nodes())
                    + "，放置 <figure data-xy-architecture=\"prepared\"><figcaption>关系说明</figcaption></figure>");
        });
        return "本轮已准备的页面素材（仅使用列出的素材；没有时采用文字布局）：\n" + (items.isEmpty() ? "无" : String.join("\n", items));
    }

    private <T> CompletableFuture<Optional<T>> prepare(boolean requested, Callable<Optional<T>> collect) {
        if (!requested) return CompletableFuture.completedFuture(Optional.empty());
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
