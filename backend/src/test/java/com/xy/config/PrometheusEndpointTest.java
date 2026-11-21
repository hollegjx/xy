package com.xy.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusEndpointTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
    static class TestApp {
        @Bean RouterFunction<ServerResponse> probe() {
            return RouterFunctions.route().GET("/probe", request -> ServerResponse.ok().body("ok")).build();
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void scrapesActualHttpAndJvmMetricsOnlyFromTheManagementPort() throws Exception {
        try (var app = new SpringApplicationBuilder(TestApp.class).profiles("monitoring").run(
                "--spring.config.location=classpath:/application-monitoring.yml", "--server.address=127.0.0.1",
                "--server.port=0", "--management.server.port=0", "--server.servlet.context-path=/api")) {
            var env = app.getEnvironment();
            int api = env.getRequiredProperty("local.server.port", Integer.class);
            int monitoring = env.getRequiredProperty("local.management.port", Integer.class);
            assertThat(api).isNotEqualTo(monitoring);
            assertThat(get(api, "/api/probe").body()).isEqualTo("ok");
            assertThat(get(api, "/api/actuator/prometheus").statusCode()).isEqualTo(404);
            var scrape = get(monitoring, "/actuator/prometheus");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("jvm_memory_used_bytes")
                    .containsPattern("(?m)^http_server_requests_seconds_count\\{[^}]*uri=\"/probe\"[^}]*} 1(?:\\.0)?$");
            assertThat(get(monitoring, "/actuator/env").statusCode()).isEqualTo(404);
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
