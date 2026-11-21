package com.xy.generation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeOutputGuardrailTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retriesOnlyOnceAndNeverDisplaysTheRejectedDraft(boolean repaired) throws Exception {
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<String>();
        var json = new ObjectMapper();
        String valid = "<!doctype html><html><body>作品</body></html>";
        gateway.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String reply = requests.size() == 1 || !repaired ? "好的" : valid;
            var payload = ("data: {\"id\":\"turn\",\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":"
                    + json.writeValueAsString(reply) + "}}]}\n\ndata: {\"id\":\"turn\",\"model\":\"test\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) { out.write(payload); }
        });
        gateway.start();
        try {
            var model = OpenAiStreamingChatModel.builder().baseUrl("http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1")
                    .apiKey("test").modelName("test").build();
            var session = mock(GenerationSession.class);
            when(session.isRunning()).thenReturn(true);
            var visible = new CopyOnWriteArrayList<String>();
            var result = new NativeCodeStream(model, true)
                    .generate(List.of(SystemMessage.from("生成网站"), UserMessage.from("展示作品")), session)
                    .doOnNext(chunk -> { if (chunk.delta() != null) visible.add(chunk.delta()); });
            if (repaired) {
                assertThat(result.blockLast(Duration.ofSeconds(5)).finalResponse().aiMessage().text()).isEqualTo(valid);
                assertThat(visible).containsExactly(valid);
            } else {
                assertThatThrownBy(() -> result.blockLast(Duration.ofSeconds(5))).isInstanceOf(OutputGuardrailException.class);
                assertThat(visible).isEmpty();
            }
            assertThat(requests).hasSize(2);
            assertThat(requests.getLast()).contains("完整的网站源码");
        } finally {
            gateway.stop(0);
        }
    }
}
