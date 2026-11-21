package com.xy.generation.service.impl;

import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.model.output.FinishReason;
import java.util.concurrent.CompletableFuture;

/** 对正常结束却只回答“好的”一类的原生生成请求，追加指令重试。 */
final class NativeOutputGuardrail implements OutputGuardrail {
    @Override
    public CompletableFuture<OutputGuardrailResult> validateAsync(OutputGuardrailRequest request) {
        return CompletableFuture.completedFuture(validate(request));
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        var response = request.responseFromLLM();
        if (response.finishReason() != FinishReason.STOP) return success(); // 截断仍按既有规则报错
        var text = response.aiMessage().text();
        return text == null || text.strip().length() < 10
                ? reprompt("未输出网站源码", "请按原约定的格式返回完整的网站源码，不要只给简短说明。")
                : success();
    }
}
