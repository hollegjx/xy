package com.xy.user.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import redis.clients.jedis.Jedis;
import redis.embedded.RedisServer;
import static org.assertj.core.api.Assertions.assertThat;

class UserAccountHttpTest {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String base;

    @Test
    void servesAccountsFromTheUserProcessAndRevokesTheSharedRedisSession(@TempDir Path dir) throws Exception {
        int redisPort = freePort();
        var redis = RedisServer.newRedisServer().bind("127.0.0.1").port(redisPort)
                .setting("dir \"" + dir + "\"").setting("save \"\"").setting("appendonly no").build();
        redis.start();
        try (var app = new SpringApplicationBuilder(UserServiceApplication.class).run(
                "--spring.config.location=classpath:/application.properties", "--server.port=0",
                "--dubbo.protocol.port=" + freePort(), "--spring.data.redis.port=" + redisPort,
                "--spring.datasource.url=jdbc:h2:mem:userHttp;MODE=MySQL;NON_KEYWORDS=USER;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=")) {
            var db = app.getBean(JdbcTemplate.class);
            db.execute("""
                    CREATE TABLE `user` (id BIGINT AUTO_INCREMENT PRIMARY KEY, userAccount VARCHAR(128) UNIQUE,
                    userPassword VARCHAR(255), userName VARCHAR(128), userRole VARCHAR(32), isDelete INT DEFAULT 0,
                    createTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updateTime TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                    """);
            int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
            base = "http://127.0.0.1:" + port + "/api";
            var credentials = mapper.createObjectNode().put("userAccount", "member").put("userPassword", "fixture-pass");
            var reg = credentials.deepCopy().put("checkPassword", "fixture-pass");
            var id = call("POST", "/users/register", reg.toString(), 200, 0);
            assertThat(id.isTextual()).isTrue();
            call("POST", "/users/register", reg.toString(), 409, 50001);
            var user = call("POST", "/users/login", credentials.toString(), 200, 0);
            assertThat(user.path("id").asText()).isEqualTo(id.asText());
            assertThat(user.has("userPassword")).isFalse();
            assertThat(cookies.getCookieStore().getCookies()).anyMatch(cookie ->
                    cookie.getName().equals("SESSION") && cookie.getPath().equals("/api") && cookie.isHttpOnly());
            call("GET", "/users/me", null, 200, 0);
            call("GET", "/users", null, 403, 40300);
            try (var data = new Jedis("127.0.0.1", redisPort)) {
                assertThat(data.keys("xy:session:sessions:*")).isNotEmpty();
                db.update("UPDATE `user` SET userRole='admin' WHERE id=?", id.asLong());
                var second = call("POST", "/users", "{\"userAccount\":\"second\",\"userPassword\":\"test-pass\"}", 200, 0);
                call("PATCH", "/users/" + second.asText(), "{\"userName\":\"更新昵称\"}", 200, 0);
                var list = call("GET", "/users?userAccount=second", null, 200, 0);
                assertThat(list.path("records").get(0).path("userName").asText()).isEqualTo("更新昵称");
                // 这个进程不能在不下线作品的情况下直接删号。
                call("DELETE", "/users/" + second.asText(), null, 405, 40000);
                var originalCookie = cookies.getCookieStore().getCookies().getFirst();
                call("POST", "/users/logout", null, 200, 0);
                cookies.getCookieStore().add(URI.create(base), originalCookie);
                call("GET", "/users/me", null, 401, 40100);
                assertThat(data.keys("xy:session:sessions:*")).isEmpty();
            }
        } finally {
            redis.stop();
        }
    }

    private JsonNode call(String method, String path, String body, int httpStatus, int code) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8));
        if (body != null) request.header("Content-Type", "application/json");
        var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(httpStatus);
        JsonNode json = mapper.readTree(response.body());
        assertThat(json.path("code").asInt(-1)).isEqualTo(code);
        return json.path("data");
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
}
