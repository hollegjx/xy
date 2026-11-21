package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xy.app.model.dto.AppAddRequest;
import com.xy.app.model.enums.CodeGenTypeEnum;
import com.xy.app.service.impl.GenerationModeRouter;
import com.xy.config.AiModelConfiguration;
import com.xy.manager.AiModelMetrics;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCallMetricsIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CountDownLatch nativeStarted = new CountDownLatch(1), releaseNative = new CountDownLatch(1);

    @Test
    void countsActualCallsAndProviderTokensWithoutMixingConcurrentApps() throws Exception {
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            gateway.setExecutor(workers);
            gateway.createContext("/v1/chat/completions", this::reply);
            gateway.start();
            new ApplicationContextRunner()
                    .withPropertyValues("spring.profiles.active=monitoring", "xy.ai.enabled=true", "xy.ai.api-key=test-only",
                            "xy.ai.base-url=http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1",
                            "xy.ai.model-name=native-model", "xy.ai.vue-model-name=vue-model", "xy.ai.routing-model-name=router")
                    .withBean(PrometheusMeterRegistry.class, () -> registry)
                    .withUserConfiguration(AiModelMetrics.class, AiModelConfiguration.class, GenerationModels.class, GenerationModeRouter.class)
                    .run(beans -> {
                        var models = beans.getBean(GenerationModels.class);
                        var nativeClient = models.forApp(11,101, CodeGenTypeEnum.HTML);
                        var vue = models.forApp(22,202, CodeGenTypeEnum.VUE_PROJECT).model();
                        var session = mock(GenerationSession.class);
                        when(session.isRunning()).thenReturn(true);
                        var first = nativeClient.writer().generate(List.of(UserMessage.from("PRIVATE_PROMPT")), session)
                                .collectList().subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).toFuture();
                        try {
                            assertThat(nativeStarted.await(5, TimeUnit.SECONDS)).isTrue();
                            var router = beans.getBean(GenerationModeRouter.class);
                            assertThat(router.select(new AppAddRequest("做邀请函","auto"), 11L)).isEqualTo(CodeGenTypeEnum.HTML);
                            assertThat(chat(vue,"第二个应用").get(5, TimeUnit.SECONDS).aiMessage().text()).isEqualTo("ok");
                            assertThatThrownBy(() -> chat(vue, "FAIL_FIXTURE").get(5,TimeUnit.SECONDS))
                                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
                        } finally {
                            releaseNative.countDown();
                        }
                        assertThat(first.get(5, TimeUnit.SECONDS).getLast().finalResponse().aiMessage().text()).isEqualTo("ok");
                        assertThat(models.forApp(11,101,CodeGenTypeEnum.HTML)).isSameAs(nativeClient);
                        String[] firstApp = {"client","native","model","native-model","user_id","11","app_id","101"};
                        String[] secondApp = {"client","vue","model","vue-model","user_id","22","app_id","202"};
                        assertThat(registry.get("xy.ai.calls").tags(firstApp).counter().count()).isEqualTo(1);
                        assertThat(registry.get("xy.ai.calls").tags(secondApp).counter().count()).isEqualTo(2);
                        assertThat(registry.get("xy.ai.calls").tags("client","routing","user_id","11","app_id","none").counter().count()).isEqualTo(1);
                        assertThat(registry.get("xy.ai.outcomes").tags(firstApp).tag("status","success").counter().count()).isEqualTo(1);
                        assertThat(registry.get("xy.ai.outcomes").tags(secondApp).tag("status","error").counter().count()).isEqualTo(1);
                        assertThat(registry.get("xy.ai.call.duration").tags(secondApp).tag("status","error").timer().count()).isEqualTo(1);
                        assertThat(registry.get("xy.ai.call.duration").tags(firstApp).timer().totalTime(TimeUnit.NANOSECONDS)).isPositive();
                        for (var token : Map.of("input",9, "output",3, "total",12).entrySet())
                            assertThat(registry.get("xy.ai.tokens").tags(firstApp).tag("type",token.getKey()).counter().count()).isEqualTo(token.getValue().doubleValue());
                        assertThat(registry.find("xy.ai.tokens").tags(secondApp).meters()).isEmpty();
                        assertThat(registry.scrape()).contains("xy_ai_calls_total", "xy_ai_call_duration_seconds_count")
                                .doesNotContain("PRIVATE_PROMPT", "PRIVATE_GATEWAY_ERROR", "test-only");
                    });
        } finally {
            releaseNative.countDown();
            gateway.stop(0);
        }
    }

    private CompletableFuture<ChatResponse> chat(StreamingChatModel model, String prompt) {
        var future = new CompletableFuture<ChatResponse>();
        model.chat(prompt, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) {}
            @Override public void onCompleteResponse(ChatResponse response) { future.complete(response); }
            @Override public void onError(Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }

    private void reply(HttpExchange ex) throws IOException {
        var request = json.readTree(ex.getRequestBody());
        String model = request.path("model").asText();
        String type = "application/json", body;
        int status = 200;
        if (request.path("messages").toString().contains("FAIL_FIXTURE")) {
            status = 400;
            body = "{\"error\":{\"message\":\"PRIVATE_GATEWAY_ERROR\"}}";
        } else if (!request.path("stream").asBoolean()) {
            body = "{\"model\":\"router\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"html\"},\"finish_reason\":\"stop\"}]}";
        } else {
            type = "text/event-stream";
            String usage = "";
            if (model.equals("native-model")) {
                nativeStarted.countDown();
                try { if (!releaseNative.await(10,TimeUnit.SECONDS)) throw new IOException("慢请求未释放"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                usage = "data: {\"id\":\"t\",\"model\":\"native-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}\n\n";
            }
            body = "data: {\"id\":\"t\",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"id\":\"t\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + usage + "data: [DONE]\n\n";
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type",type);
        ex.sendResponseHeaders(status,bytes.length);
        try (var stream = ex.getResponseBody()) { stream.write(bytes); }
    }
}
