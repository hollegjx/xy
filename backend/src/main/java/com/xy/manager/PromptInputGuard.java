package com.xy.manager;

import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.InputGuardrailExecutor;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.invocation.InvocationContext;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 创建和续改共用的输入护轨，只检查本轮用户原文。 */
public final class PromptInputGuard implements InputGuardrail {

    private static final String REJECTION = "需求中包含修改模型指令、索取内部提示词或绕过安全限制的要求，请改为描述网站的内容或功能";
    private static final InputGuardrailExecutor EXECUTOR = InputGuardrailExecutor.builder()
            .guardrails(new PromptInputGuard()).build();
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final List<Pattern> RULES = List.of(
            rule("(?:忽略|无视|忘记|覆盖)掉?\\s*(?:之前|先前|此前|以上|上面|上述|系统|开发者)"
                            + "的?(?:所有|全部)?(?:系统|开发者)?(?:指令|规则|提示词)",
                    "(?:ignore|disregard|forget|override)\\s+(?:(?:all|the|your)\\s+){0,3}"
                            + "(?:previous|prior|above|system|developer)\\s+(?:(?:system|developer)\\s+)?(?:instructions?|rules?|prompts?)"),
            rule("(?:输出|显示|泄露|透露|打印|告诉我)\\s*(?:你的|内部的?)?(?:系统|开发者)的?(?:提示词|指令)",
                    "(?:reveal|show|print|repeat)\\s+(?:(?:me|the|your|original|hidden)\\s+){0,3}"
                            + "(?:system|developer)\\s+(?:prompts?|instructions?)"),
            rule("(?:绕过|破解|禁用)(?:(?:模型|平台|系统)的?)?(?:安全(?:检查|限制|策略|过滤)|权限校验|身份验证)"
                            + "|(?:开启|启用|进入)越狱模式",
                    "(?:bypass|disable)\\s+(?:(?:the|your|model)\\s+){0,3}"
                            + "(?:guardrails?|(?:security|safety)\\s+(?:checks?|filters?|restrictions?)|authentication)"
                            + "|(?:enable|enter)\\s+jailbreak\\s+mode"));
    private static final Pattern NEGATION = Pattern.compile(
            "(?:不要|不得|请勿|禁止|避免|防止|不能|不应|\\bnever|\\bdo\\s+not|\\bdon['’]t)\\s*$", FLAGS);

    public PromptInputGuard() {}

    public static RuntimeException rejection(InputGuardrailException error) {
        return error.getCause() == null ? new BusinessException(ErrorCode.PARAMS_ERROR, REJECTION) : error;
    }

    private static Pattern rule(String chinese, String english) {
        return Pattern.compile("(?:" + chinese + ")|\\b(?:" + english + ")\\b", FLAGS);
    }

    public static void check(String prompt) {
        UserMessage message = UserMessage.from(prompt);
        var params = GuardrailRequestParams.builder().userMessageTemplate(prompt).variables(Map.of())
                .invocationContext(InvocationContext.builder().userMessage(message).build()).build();
        try {
            EXECUTOR.execute(InputGuardrailRequest.builder().userMessage(message).commonParams(params).build());
        } catch (InputGuardrailException rejected) {
            throw rejection(rejected);
        }
    }

    @Override
    public InputGuardrailResult validate(UserMessage message) {
        String text = Normalizer.normalize(message.singleText(), Normalizer.Form.NFKC);
        var negation = NEGATION.matcher(text);
        for (var rule : RULES) {
            var match = rule.matcher(text);
            while (match.find()) {
                // “不要绕过安全检查”是正常需求；否定词只作用于紧随其后的这一处。
                if (!negation.region(Math.max(0, match.start() - 24), match.start()).find()) {
                    return fatal(REJECTION);
                }
            }
        }
        return success();
    }
}
