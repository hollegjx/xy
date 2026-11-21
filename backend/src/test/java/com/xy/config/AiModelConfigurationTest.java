package com.xy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelConfigurationTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "test-classifier")
    void usesConfiguredModelsForClassificationAndStreamingGeneration(String routingModel) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CompletableFuture<JsonNode> classification = new CompletableFuture<>();
        CompletableFuture<JsonNode> requestBody = new CompletableFuture<>();
        CompletableFuture<String> auth = new CompletableFuture<>();
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/v1/chat/completions", exchange -> {
            var sent = mapper.readTree(exchange.getRequestBody());
            if (!sent.path("stream").asBoolean()) {
                classification.complete(sent);
                byte[] response = """
                        {"choices":[{"index":0,"message":{"role":"assistant","content":"html"},"finish_reason":"stop"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var body = exchange.getResponseBody()) { body.write(response); }
                return;
            }
            requestBody.complete(sent);
            auth.complete(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("data: {\"id\":\"test\",\"model\":\"gpt-6.1-sol\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"role\":\"assistant\",\"content\":\"<!DOCTYPE html>\"}}]}\n\n"
                    + "data: {\"id\":\"test\",\"model\":\"gpt-6.1-sol\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        gateway.start();
        try {
            var context = new ApplicationContextRunner().withUserConfiguration(AiModelConfiguration.class)
                    .withPropertyValues("xy.ai.enabled=true", "xy.ai.api-key=test-only-key", "xy.ai.model-name=gpt-6.1-sol",
                            "xy.ai.timeout-seconds=5", "xy.ai.base-url=http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1");
            if (routingModel != null) context = context.withPropertyValues("xy.ai.routing-model-name=" + routingModel);
            context.run(beans -> {
                assertThat(beans.getBean(StreamingChatModel.class)).isNotSameAs(beans.getBean(StreamingChatModel.class));
                assertThat(beans.getBean(ChatModel.class).chat("选择生成模式")).isEqualTo("html");
                var route = classification.get(1, TimeUnit.SECONDS);
                assertThat(route.path("model").asText()).isEqualTo(
                        routingModel == null || routingModel.isEmpty() ? "gpt-6.1-sol" : routingModel);
                assertThat(route.path("max_completion_tokens").asInt()).isEqualTo(512);

                CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
                StringBuilder partial = new StringBuilder();
                beans.getBean(StreamingChatModel.class).chat("生成一个网站", new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String text) { partial.append(text); }
                    @Override
                    public void onCompleteResponse(ChatResponse response) { completed.complete(response); }
                    @Override
                    public void onError(Throwable error) { completed.completeExceptionally(error); }
                });
                ChatResponse result = completed.get(8, TimeUnit.SECONDS);
                JsonNode sent = requestBody.get(1, TimeUnit.SECONDS);
                assertThat(sent.path("model").asText()).isEqualTo("gpt-6.1-sol");
                assertThat(sent.path("stream").asBoolean()).isTrue();
                assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(16384);
                assertThat(auth.get()).isEqualTo("Bearer test-only-key");
                assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
                assertThat(partial.toString()).isEqualTo("<!DOCTYPE html>");
            });
        } finally {
            gateway.stop(0);
        }
    }
}
