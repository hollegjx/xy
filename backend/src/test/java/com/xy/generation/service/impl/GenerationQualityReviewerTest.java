package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xy.config.AiModelConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationQualityReviewerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typedReportUsesGatewaySchemaOnlyWhenSupported(boolean schema) throws Exception {
        var mapper = new ObjectMapper();
        var captured = new CompletableFuture<JsonNode>();
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/v1/chat/completions", exchange -> {
            captured.complete(mapper.readTree(exchange.getRequestBody()));
            var content = "{\"isValid\":false,\"errors\":[\"按钮未绑定\"],\"suggestions\":[\"补上点击事件\"]}";
            var chunks = List.of(Map.of("delta", Map.of("role", "assistant", "content", content)),
                    Map.of("delta", Map.of(), "finish_reason", "stop"));
            var body = new StringBuilder();
            for (var chunk : chunks) body.append("data: ").append(mapper.writeValueAsString(
                    Map.of("id", "review", "model", "local-review", "choices", List.of(chunk)))).append("\n\n");
            var bytes = body.append("data: [DONE]\n\n").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        gateway.start();
        try {
            var upstream = new AiModelConfiguration(java.util.Optional.empty()).streamingChatModel(
                    "http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1", "test-only", "local-review", 5, 2000, schema);
            var session = new GenerationSession(1, 2, 3, new SseEmitter(), mock(GenerationPersistence.class),
                    mock(GenerationSession.OutputHandler.class), () -> {}, mock(ScheduledExecutorService.class));
            var result = GenerationQualityReviewer.check(upstream, session, new CompletableFuture<>(), mapper,
                    "html", "让按钮可以点击").get(8, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(new GenerationQualityReviewer.Report(false, List.of("按钮未绑定"), List.of("补上点击事件")));
            var request = captured.get(1, TimeUnit.SECONDS);
            assertThat(request.path("model").asText()).isEqualTo("local-review");
            assertThat(request.path("messages").get(0).path("content").asText()).contains("本次生成类型：html");
            String input = request.path("messages").get(1).path("content").asText();
            if (schema) {
                var format = request.get("response_format");
                assertThat(format.path("type").asText()).isEqualTo("json_schema");
                var contract = format.get("json_schema");
                assertThat(contract.path("strict").asBoolean()).isTrue();
                var properties = contract.path("schema").path("properties");
                assertThat(properties.path("isValid").path("type").asText()).as("schema: %s", contract).isEqualTo("boolean");
                assertThat(properties.path("errors").path("items").path("type").asText()).isEqualTo("string");
                assertThat(properties.has("suggestions")).isTrue();
                assertThat(input).isEqualTo("让按钮可以点击");
            } else {
                assertThat(request.has("response_format")).isFalse();
                assertThat(input).contains("让按钮可以点击", "isValid", "errors", "suggestions");
            }
        } finally { gateway.stop(0); }
    }
}
