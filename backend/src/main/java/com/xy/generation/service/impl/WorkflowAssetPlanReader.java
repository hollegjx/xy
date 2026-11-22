package com.xy.generation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.output.ServiceOutputParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型按同一个格式列任务，但单张坏图不能影响其他素材。 */
final class WorkflowAssetPlanReader {
    private final ObjectMapper mapper;
    private final JsonNode root;
    private final List<WorkflowAssetPlan.DiagramTask> diagrams = new ArrayList<>();
    private final Map<Integer, ObjectNode> invalid = new LinkedHashMap<>();

    WorkflowAssetPlanReader(ObjectMapper mapper, Result<String> result) {
        this.mapper = mapper;
        root = object(result);
        for (var task : items(root, "architectures")) {
            if (!task.isObject()) continue;
            String purpose = task.path("purpose").textValue();
            var design = diagram(task.path("design"));
            if (design == null) {
                var input = mapper.createObjectNode();
                if (purpose != null) input.put("purpose", purpose);
                if (task.path("design") instanceof ObjectNode raw) {
                    var relations = raw.deepCopy().retain("nodes", "edges");
                    for (var edge : items(relations, "edges"))
                        if (edge instanceof ObjectNode fields) fields.retain("from", "to", "label");
                    input.set("design", relations);
                }
                invalid.put(diagrams.size(), input);
            }
            diagrams.add(new WorkflowAssetPlan.DiagramTask(purpose, design));
        }
    }

    boolean needsCorrection() { return !invalid.isEmpty(); }
    String correctionRequest() { return mapper.valueToTree(invalid).toString(); }

    void applyCorrections(Result<String> result) {
        for (var item : items(object(result), "diagrams")) {
            var index = item.path("index");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || !invalid.containsKey(index.intValue())) continue;
            var design = diagram(item.path("design"));
            if (design == null) continue;
            int i = index.intValue();
            diagrams.set(i, new WorkflowAssetPlan.DiagramTask(diagrams.get(i).purpose(), design));
            invalid.remove(i);
        }
    }

    WorkflowAssetPlan plan() {
        return new WorkflowAssetPlan(read("photos", WorkflowAssetPlan.Themed.class),
                read("illustrations", WorkflowAssetPlan.Themed.class), logos(), diagrams);
    }

    private List<WorkflowAssetPlan.LogoTask> logos() {
        var tasks = new ArrayList<WorkflowAssetPlan.LogoTask>();
        for (var item : items(root, "logos")) {
            if (!item.isObject()) continue;
            var raw = item.path("design");
            WorkflowAssetPlan.Logo design = null;
            if (VueBrandLogo.acceptsDesign(raw)) {
                try { design = mapper.treeToValue(raw, WorkflowAssetPlan.Logo.class); }
                catch (JsonProcessingException | IllegalArgumentException ignored) { /* 仅此位置改用文字。 */ }
            }
            tasks.add(new WorkflowAssetPlan.LogoTask(item.path("purpose").textValue(), design));
        }
        return tasks;
    }

    private JsonNode object(Result<String> result) {
        if (result.finishReason() != FinishReason.STOP || result.content() == null)
            throw new IllegalArgumentException("素材计划不完整");
        // 与 AI Service 的结构化回复使用同一解码器（包括 JSON 代码围栏），强类型转换留到每项。
        return mapper.valueToTree(new ServiceOutputParser().parseText(Map.class, result.content()));
    }

    private <T> List<T> read(String key, Class<T> type) {
        var valid = new ArrayList<T>();
        for (var item : items(root, key)) {
            if (!item.isObject()) continue;
            try { valid.add(mapper.treeToValue(item, type)); }
            catch (JsonProcessingException | IllegalArgumentException ignored) { /* 仅跳过这一处素材 */ }
        }
        return valid;
    }

    private static Iterable<JsonNode> items(JsonNode object, String key) {
        var list = object.path(key);
        return list.isArray() ? list : List.of();
    }

    private static VueArchitectureDiagram.Diagram diagram(JsonNode design) {
        try { return VueArchitectureDiagram.parse(design); }
        catch (IllegalArgumentException invalidDesign) { return null; }
    }
}
