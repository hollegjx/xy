package com.xy.generation.service.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.Optional;

/** 模型看到的方法就是素材批次调用的方法。图像和 SVG 留在本地，模型只收保存后的回执。 */
final class VueAssetTool {
    static ToolSpecification describe(Class<?> type) {
        var spec = ToolSpecifications.toolSpecificationsFrom(type).getFirst();
        return spec.toBuilder().parameters(closed(spec.parameters())).build();
    }

    private static JsonObjectSchema closed(JsonObjectSchema object) {
        var builder = object.toBuilder().additionalProperties(false);
        object.properties().forEach((name, field) -> {
            if (field instanceof JsonObjectSchema nested) builder.addProperty(name, closed(nested));
            else if (field instanceof JsonArraySchema array && array.items() instanceof JsonObjectSchema item)
                builder.addProperty(name, JsonArraySchema.builder().description(array.description()).items(closed(item)).build());
        });
        return builder.build();
    }

    static <T> Optional<T> call(Object target, ToolExecutionRequest call, Class<T> resultType) {
        var executor = new DefaultToolExecutor(target, call);
        ToolExecutionResult result;
        try {
            result = executor.executeWithContext(call, InvocationContext.builder().build());
        } catch (RuntimeException invalidArguments) {
            return Optional.empty();
        }
        if (result.isError()) throw new IllegalStateException("素材工具执行失败：" + call.name());
        return Optional.ofNullable(resultType.cast(result.result()));
    }

    private VueAssetTool() {}
}
