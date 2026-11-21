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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** 首次原生工作流的素材只保留在本轮。图状态和模型上下文仅传计划、标记和署名。 */
final class NativeWorkflowAssets {
    record Plan(String photoTheme, String illustrationTheme) {}

    interface Planner {
        @SystemMessage("""
                你是 XY 页面素材规划师。按用户需求选素材，不写页面代码。适量选用；明确不要图片时所有项填 null。
                photoTheme 只能是以下公开主题之一或 null：{{themes}}。不要在主题里填写姓名、地址或自由搜索词。
                illustrationTheme 只可选 reading（学习阅读）、teamwork（团队协作）、analytics（数据分析），否则填 null。
                """)
        @dev.langchain4j.service.UserMessage("{{prompt}}")
        CompletableFuture<Result<Plan>> plan(@V("prompt") String prompt, @V("themes") String themes);
    }

    private final ObjectMapper mapper;
    private final NativeContentPhoto photo;
    private final WorkflowAssetTasks tasks;
    private Map<String, ThemeIllustrations.Illustration> illustrations = Map.of();

    NativeWorkflowAssets(ObjectMapper mapper, NativeContentPhoto photo, Executor executor) {
        this.mapper = mapper;
        this.photo = photo;
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
        var jobs = new ArrayList<CompletableFuture<String>>();
        if (plan.photoTheme() != null && OpenversePhotoSearch.themes().contains(plan.photoTheme())) {
            jobs.add(CompletableFuture.supplyAsync(() -> {
                var credit = photo.collect(plan.photoTheme(), session);
                return credit == null ? "" : "内容照片：<img data-xy-photo=\"" + plan.photoTheme()
                        + "\" alt=\"填写准确的替代文字\">；不用写 src。许可 " + credit.license() + "，来源 " + credit.source();
            }, tasks));
        }
        if (plan.illustrationTheme() != null) jobs.add(CompletableFuture.supplyAsync(() -> {
            try {
                var art = ThemeIllustrations.load(plan.illustrationTheme());
                if (art.isEmpty()) return "";
                illustrations = Map.of(plan.illustrationTheme(), art.get());
                return "主题插画：" + art.get().label() + "，在正文放置 <!--xy:illustration:" + plan.illustrationTheme() + "-->";
            } catch (IOException unavailable) { return ""; }
        }, tasks));
        tasks.await(jobs);
        if (!session.isRunning()) return "";
        String items = jobs.stream().map(CompletableFuture::join).filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        return "本轮已准备的照片和插画（仅使用列出的素材；没有时采用文字布局）：\n" + (items.isEmpty() ? "无" : items);
    }

    static void enhance(List<ChatMessage> conversation, String manifest) {
        var user = (UserMessage) conversation.getLast();
        conversation.set(conversation.size() - 1, UserMessage.from(user.singleText() + "\n\n" + manifest));
    }

    Map<String, ThemeIllustrations.Illustration> illustrations() { return illustrations; }
    void stop() { tasks.stop(); }
}
