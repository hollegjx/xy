package com.xy.manager;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("monitoring")
public class AiModelMetrics {
    private record Owner(Long user, Long app) {}
    private record Start(Tags tags, String model, Timer.Sample clock) {}
    private static final ThreadLocal<Owner> CLIENT_OWNER = new ThreadLocal<>();
    private final MeterRegistry registry;

    public AiModelMetrics(MeterRegistry registry) { this.registry = registry; }

    /** 创建 prototype 时绑定归属；已缓存的客户端及其异步回调不再依赖调用线程。 */
    public static <T> T forClient(Long user, Long app, Supplier<T> factory) {
        var previous = CLIENT_OWNER.get();
        CLIENT_OWNER.set(new Owner(user, app));
        try {
            return factory.get();
        } finally {
            if (previous == null) CLIENT_OWNER.remove(); else CLIENT_OWNER.set(previous);
        }
    }

    public ChatModelListener listener(String client, String configuredModel) {
        var owner = Objects.requireNonNullElseGet(CLIENT_OWNER.get(), () -> new Owner(null, null));
        var base = Tags.of("client", client, "user_id", Objects.toString(owner.user(), "none"),
                "app_id", Objects.toString(owner.app(), "none"));
        return new ChatModelListener() {
            private final Object requestKey = new Object();

            @Override public void onRequest(ChatModelRequestContext ctx) {
                String model = Objects.requireNonNullElse(ctx.chatRequest().modelName(), configuredModel);
                var tags = base.and("model", model);
                ctx.attributes().put(requestKey, new Start(tags, model, Timer.start(registry)));
                registry.counter("xy.ai.calls", tags).increment();
            }

            @Override public void onResponse(ChatModelResponseContext ctx) {
                var response = ctx.chatResponse();
                var reason = response.finishReason();
                var start = finish(ctx.attributes(), "success", reason == null ? "UNKNOWN" : reason.name());
                var usage = response.tokenUsage();
                if (start == null || usage == null) return;
                tokens(start.tags(), "input", usage.inputTokenCount());
                tokens(start.tags(), "output", usage.outputTokenCount());
                tokens(start.tags(), "total", usage.totalTokenCount());
            }

            @Override public void onError(ChatModelErrorContext ctx) {
                // 异常类型可聚合；原始错误可能包含模型网关返回的用户内容或凭据。
                finish(ctx.attributes(), "error", ctx.error().getClass().getSimpleName());
            }

            private Start finish(Map<Object, Object> attributes, String status, String reason) {
                var start = (Start) attributes.remove(requestKey);
                if (start != null) {
                    var tags = start.tags().and("status", status);
                    registry.counter("xy.ai.outcomes", tags.and("reason", reason)).increment();
                    long nanos = start.clock().stop(registry.timer("xy.ai.call.duration", tags));
                    // 分位数针对模型比较；不给每个用户和应用再分配整组直方图桶。
                    Timer.builder("xy.ai.model.latency").tags("model", start.model(), "client", client, "status", status)
                            .publishPercentileHistogram().maximumExpectedValue(Duration.ofMinutes(10))
                            .register(registry).record(nanos, TimeUnit.NANOSECONDS);
                }
                return start;
            }
        };
    }

    private void tokens(Tags tags, String type, Integer count) {
        if (count != null && count >= 0) registry.counter("xy.ai.tokens", tags.and("type", type)).increment(count);
    }
}
