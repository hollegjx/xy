package com.xy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelConfigurationTest {

    @Test
    void usesConfiguredGatewayAndModelAndReadsChatCompletionChunks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CompletableFuture<JsonNode> requestBody = new CompletableFuture<>();
        CompletableFuture<String> auth = new CompletableFuture<>();
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/v1/chat/completions", exchange -> {
            requestBody.complete(mapper.readTree(exchange.getRequestBody()));
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
            var model = new AiModelConfiguration().streamingChatModel(
                    "http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1",
                    "test-only-key", "gpt-6.1-sol", 5, 16384);
            CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
            StringBuilder partial = new StringBuilder();
            model.chat("生成一个网站", new StreamingChatResponseHandler() {
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
        } finally {
            gateway.stop(0);
        }
    }
}
