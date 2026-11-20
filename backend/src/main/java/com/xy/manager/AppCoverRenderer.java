package com.xy.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppCoverRenderer {
    private final boolean enabled;
    private final String docker;

    public AppCoverRenderer(@Value("${xy.cover.enabled:false}") boolean enabled,
            @Value("${xy.vue-build.docker-executable:docker}") String docker) {
        this.enabled = enabled;
        this.docker = docker;
    }

    /** 只传入发布快照，容器无网络、宿主目录或平台凭证。 */
    public byte[] render(String html) throws IOException {
        if (!enabled) return null;
        var source = Files.createTempFile("xy-cover-", ".html");
        try {
            Files.writeString(source, html);
            var image = Files.createTempFile("xy-cover-", ".jpg");
            try {
                var process = new ProcessBuilder(docker, "run", "--rm", "-i", "--pull=never",
                        "--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                        "--memory=512m", "--cpus=1", "--pids-limit=128", "--tmpfs", "/tmp:size=128m,mode=1777",
                        "xy-cover:1").redirectInput(source.toFile()).redirectOutput(image.toFile())
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try {
                    if (!process.waitFor(16, TimeUnit.SECONDS) || process.exitValue() != 0 || Files.size(image) == 0)
                        throw new IOException("封面截图未完成");
                    return Files.readAllBytes(image);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("封面截图中断", interrupted);
                } finally {
                    if (process.isAlive()) process.destroyForcibly();
                }
            } finally {
                Files.deleteIfExists(image);
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }
}
