package com.xy.screenshot.service;

import com.xy.client.ScreenshotService;
import com.xy.screenshot.DockerScreenshotRenderer;
import java.io.IOException;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;

@DubboService(interfaceClass = ScreenshotService.class, retries = 0, timeout = 22_000)
public class ScreenshotProvider implements ScreenshotService {
    private final ScreenshotService renderer;

    public ScreenshotProvider(@Value("${xy.screenshot.docker-executable:docker}") String docker,
                              @Value("${xy.screenshot.renderer-socket:}") String socket) {
        renderer = socket.isBlank() ? new DockerScreenshotRenderer(docker) : new SocketScreenshotRenderer(socket);
    }

    @Override
    public byte[] render(String snapshot) throws IOException {
        return renderer.render(snapshot);
    }
}
