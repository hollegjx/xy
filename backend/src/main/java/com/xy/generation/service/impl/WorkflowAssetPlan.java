package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.output.ServiceOutputParser;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 规划只描述页面需要什么；生成模式决定怎么取得素材、以及页面最终引用哪些文件。 */
record WorkflowAssetPlan(List<Themed> photos, List<Themed> illustrations, List<LogoTask> logos, List<DiagramTask> architectures) {
    record Layer(String shape, String color, List<Integer> coordinates) {}
    record Logo(String name, String brief, List<Layer> layers) {}
    record Themed(String theme, String purpose) {}
    record LogoTask(String purpose, Logo design) {}
    record DiagramTask(String purpose, VueArchitectureDiagram.Diagram design) {}
    record DiagramCorrection(Integer index, VueArchitectureDiagram.Diagram design) {}
    record Corrections(List<DiagramCorrection> diagrams) {}
    private static final ServiceOutputParser FORMAT = new ServiceOutputParser();
    private static final String PLAN_FORMAT = FORMAT.outputFormatInstructions(WorkflowAssetPlan.class);
    private static final String CORRECTION_FORMAT = FORMAT.outputFormatInstructions(Corrections.class);

    WorkflowAssetPlan {
        photos = entries(photos);
        illustrations = entries(illustrations);
        logos = entries(logos);
        architectures = entries(architectures);
    }

    private static <T> List<T> entries(List<T> items) {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }

    interface Planner {
        @SystemMessage("""
                你是 XY 页面素材规划师。按用户需求选素材，不写页面代码。photos、illustrations、logos、architectures 各是任务列表，不需要时填 []。
                每张图片各占一项，purpose 描述该图在页面的用途或位置。需要同主题的不同照片，可为每张分别列一项。
                照片 theme 只能是公开主题：{{themes}}。不要在主题里填写姓名、地址或自由搜索词。
                插画 theme 只可选 reading（学习阅读）、teamwork（团队协作）、analytics（数据分析）。
                每个 Logo 或架构图任务分别填写 purpose 和 design。Logo 的 design.name 为品牌名，brief 仅写图形意象，不含用户原文或个人信息。
                layers 用六位十六进制色，坐标 0–100：circle 是 [圆心x,圆心y,半径]，line 和 polygon 分别是至少两对、三对 x,y。
                架构图的 design.nodes 为不重复的模块名，edges 的 from、to 必须是其中的名称，label 可省略。
                {{format}}
                """)
        @dev.langchain4j.service.UserMessage("{{prompt}}")
        CompletableFuture<Result<String>> plan(@V("prompt") String prompt, @V("themes") String themes, @V("format") String format);

        @SystemMessage("只修正所列架构图的节点和连线，保留原用途。每条的 index 使用输入中的编号，from 和 to 都必须出现在该图的 nodes 中。\n{{format}}")
        @dev.langchain4j.service.UserMessage("{{diagrams}}")
        CompletableFuture<Result<String>> correct(@V("diagrams") String invalidDiagrams, @V("format") String format);
    }

    static CompletableFuture<WorkflowAssetPlan> request(StreamingChatModel model, GenerationSession session,
            CompletableFuture<?> node, String prompt, ObjectMapper mapper, boolean correctDiagrams) {
        session.onPartialResponse("\n正在规划页面素材…\n");
        var adapter = WorkflowNodeModel.adapt(model, session, node, Set.of(), ignored -> {});
        var planner = AiServices.builder(Planner.class).chatModel(adapter).build();
        return planner.plan(prompt, String.join("、", OpenversePhotoSearch.themes()), PLAN_FORMAT).thenCompose(result -> {
            var reply = new WorkflowAssetPlanReader(mapper, result);
            if (!correctDiagrams || !reply.needsCorrection() || !session.isRunning())
                return CompletableFuture.completedFuture(reply.plan());
            session.onPartialResponse("\n正在修正架构图关系（最多一次）…\n");
            try {
                return planner.correct(reply.correctionRequest(), CORRECTION_FORMAT)
                        .thenAccept(reply::applyCorrections).handle((ignored, error) -> reply.plan());
            } catch (RuntimeException unavailable) { return CompletableFuture.completedFuture(reply.plan()); }
        });
    }
}
