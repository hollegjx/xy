package com.xy.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VueProjectBuildManager {
    private final boolean enabled;
    private final String executable;
    private final String image;
    private final long timeoutSeconds;

    public VueProjectBuildManager(@Value("${xy.vue-build.enabled:false}") boolean enabled,
            @Value("${xy.vue-build.docker-executable:docker}") String executable,
            @Value("${xy.vue-build.image:xy-vue-builder:local}") String image,
            @Value("${xy.vue-build.timeout-seconds:120}") long timeoutSeconds) {
        this.enabled = enabled;
        this.executable = executable;
        this.image = image;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean enabled() { return enabled; }

    public void build(GeneratedFileManager.VueBuildPaths paths, BooleanSupplier running) throws IOException {
        String name = "xy-build-" + UUID.randomUUID();
        String user = Files.getAttribute(paths.source(), "unix:uid") + ":" + Files.getAttribute(paths.source(), "unix:gid");
        Process process = start(List.of(executable, "run", "--rm", "--pull=never", "--name", name,
                "--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user", user, "--tmpfs", "/tmp:rw,nosuid", "--mount",
                "type=bind,source=" + paths.source() + ",target=/source,readonly", "--mount",
                "type=bind,source=" + paths.output() + ",target=/output", image));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (!running.getAsBoolean() || System.nanoTime() >= deadline) {
                    throw new IOException("构建已停止或超时");
                }
            }
            if (!running.getAsBoolean() || process.exitValue() != 0) throw new IOException("构建未成功");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("构建被中断", error);
        } finally {
            process.destroyForcibly();
            // 终止客户端不保证容器结束，显式清理本次唯一命名的容器。
            boolean interrupted = Thread.interrupted();
            try {
                Process cleanup = start(List.of(executable, "rm", "--force", name));
                if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) interrupted = true;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
}
