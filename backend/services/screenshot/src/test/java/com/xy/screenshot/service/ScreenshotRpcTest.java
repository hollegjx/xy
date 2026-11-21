package com.xy.screenshot.service;

import com.xy.client.dubbo.DubboScreenshotClient;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScreenshotRpcTest {
    @Test
    void sendsThePublishedSnapshotAndReturnsImageBytesAcrossTwoJvms(@TempDir Path directory) throws Exception {
        var image = directory.resolve("cover.jpg");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", image.toFile());
        String snapshot = "<!doctype html><p>本次发布的作品</p>";
        Files.writeString(directory.resolve("snapshot.html"), snapshot);
        var docker = directory.resolve("docker-stub");
        Files.writeString(docker, """
                #!/bin/sh
                set -eu
                cd "$(dirname "$0")"
                cmp -s - snapshot.html
                cat cover.jpg
                """);
        assertTrue(docker.toFile().setExecutable(true));
        var loopback = InetAddress.getByName("::1");
        int port;
        try (var socket = new ServerSocket(0, 1, loopback)) { port = socket.getLocalPort(); }
        Path log = directory.resolve("provider.log");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ScreenshotApplication.class.getName(), "--dubbo.protocol.port=" + port,
                "--xy.screenshot.docker-executable=" + docker).redirectErrorStream(true).redirectOutput(log.toFile());
        // 应用先启动，截图服务稍后加入；发布不需要等截图进程先上线。
        try (var client = new DubboScreenshotClient("tri://[::1]:" + port)) {
            var provider = command.start();
            try {
                awaitListener(provider, new InetSocketAddress(loopback, port), log);
                assertArrayEquals(Files.readAllBytes(image), client.render(snapshot));
            } finally {
                provider.destroy();
                if (!provider.waitFor(5, TimeUnit.SECONDS)) provider.destroyForcibly().waitFor();
            }
        }
    }

    private void awaitListener(Process process, InetSocketAddress socket, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (var probe = new Socket()) {
                probe.connect(socket, 150);
                return;
            } catch (IOException stillStarting) {
                Thread.sleep(100);
            }
        }
        fail("独立截图进程未监听回环地址：" + Files.readString(log));
    }
}
