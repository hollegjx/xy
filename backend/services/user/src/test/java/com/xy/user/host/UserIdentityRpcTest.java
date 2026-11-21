package com.xy.user.host;

import com.xy.client.UserIdentityLookup;
import com.xy.client.dubbo.DubboDirectReference;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class UserIdentityRpcTest {
    @Test
    void readsLiveRolesAndLogicalDeletionAcrossTwoJvms(@TempDir Path directory) throws Exception {
        String options = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER";
        var database = Server.createTcpServer("-tcpPort", "0", "-ifExists");
        try (var db = DriverManager.getConnection("jdbc:h2:mem:identities" + options, "sa", "");
             var sql = db.createStatement()) {
            sql.execute("""
                    CREATE TABLE `user` (id BIGINT PRIMARY KEY, userAccount VARCHAR(128), userName VARCHAR(128),
                        userPassword VARCHAR(128), userRole VARCHAR(32), createTime TIMESTAMP, updateTime TIMESTAMP,
                        isDelete INT NOT NULL DEFAULT 0)
                    """);
            sql.execute("INSERT INTO `user` (id, userRole) VALUES (42, 'user')");
            database.start();
            int port;
            try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            Path log = directory.resolve("user-service.log");
            var provider = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.net.preferIPv4Stack=true", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    UserServiceApplication.class.getName(), "--spring.main.web-application-type=none", "--dubbo.protocol.port=" + port,
                    "--spring.datasource.url=jdbc:h2:tcp://127.0.0.1:" + database.getPort() + "/mem:identities" + options,
                    "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=")
                    .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                await().atMost(Duration.ofSeconds(45)).until(() -> !provider.isAlive()
                        || Files.readString(log).contains("Started UserServiceApplication"));
                assertTrue(provider.isAlive(), () -> "独立用户服务启动失败，见 " + log);
                try (var client = new DubboDirectReference<>(UserIdentityLookup.class, "tri://127.0.0.1:" + port, 3000)) {
                    var users = client.service();
                    var member = users.findById(42L);
                    assertNotNull(member);
                    assertEquals(42L, member.getId());
                    assertEquals("user", member.getUserRole());
                    sql.executeUpdate("UPDATE `user` SET userRole='admin' WHERE id=42");
                    assertEquals("admin", users.findById(42L).getUserRole());
                    sql.executeUpdate("UPDATE `user` SET isDelete=1 WHERE id=42");
                    assertNull(users.findById(42L));
                    assertNull(users.findById(99L));
                }
            } finally {
                provider.destroy();
                if (!provider.waitFor(5, TimeUnit.SECONDS)) provider.destroyForcibly().waitFor();
            }
        } finally {
            database.stop();
        }
    }
}
