package com.xy.config;

import com.xy.client.ScreenshotService;
import com.xy.client.dubbo.DubboScreenshotClient;
import com.xy.screenshot.DockerScreenshotRenderer;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotConfigurationTest {
    private final ApplicationContextRunner platform = new ApplicationContextRunner()
            .withUserConfiguration(ScreenshotConfiguration.class);

    @Test
    void keepsOriginalLocalModeAndDoesNotConnectWhenCoversAreDisabled() {
        platform.withPropertyValues("xy.cover.rpc-url=unused").run(context ->
                assertThat(context).doesNotHaveBean(ScreenshotService.class));
        platform.withPropertyValues("xy.cover.enabled=true").run(context ->
                assertThat(context.getBean(ScreenshotService.class)).isInstanceOf(DockerScreenshotRenderer.class));
    }

    @Test
    void canStartWithRemoteCoversBeforeTheProviderIsAvailable() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("::1"))) { port = socket.getLocalPort(); }
        platform.withPropertyValues("xy.cover.enabled=true", "xy.cover.rpc-url=tri://[::1]:" + port)
                .run(context -> assertThat(context.getBean(ScreenshotService.class))
                        .isInstanceOf(DubboScreenshotClient.class));
    }
}
