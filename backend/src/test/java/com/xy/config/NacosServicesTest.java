package com.xy.config;

import com.xy.client.ScreenshotService;
import com.xy.client.UserIdentityLookup;
import com.xy.common.SessionUserResolver;
import com.xy.common.UserConstant;
import com.xy.exception.BusinessException;
import com.xy.user.model.dto.UserIdentity;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.ResolvableType;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class NacosServicesTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "XY_TEST_NACOS_ADDRESS", matches = "nacos://127[.]0[.]0[.]1:[0-9]+")
    void discoversLateProvidersAndFindsTheReplacementWithoutChangingConsumerAddresses() throws Exception {
        String registry = System.getenv("XY_TEST_NACOS_ADDRESS") + "?group=xy-test-" + UUID.randomUUID();
        try (var platform = new SpringApplicationBuilder(NacosServicesConfiguration.class)
                .web(WebApplicationType.NONE).profiles("nacos")
                .run("--dubbo.registry.address=" + registry, "--xy.cover.enabled=true")) {
            var users = platform.<SessionUserResolver<UserIdentity>>getBeanProvider(ResolvableType.forClassWithGenerics(
                    SessionUserResolver.class, UserIdentity.class)).getObject();
            var screenshots = platform.getBean(ScreenshotService.class);
            var request = new MockHttpServletRequest();
            request.getSession().setAttribute(UserConstant.LOGIN_USER_ID, 42L);
            assertEquals(50300, assertThrows(BusinessException.class, () -> users.require(request)).getCode());
            assertEquals(42L, request.getSession().getAttribute(UserConstant.LOGIN_USER_ID));

            var first = provider("xy-user", registry, UserIdentityLookup.class, id -> new UserIdentity(id, "user"));
            try {
                await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                        .untilAsserted(() -> assertEquals("user", users.require(request).getUserRole()));
            } finally {
                first.destroy();
            }
            var next = provider("xy-user", registry, UserIdentityLookup.class, id -> new UserIdentity(id, "admin"));
            try {
                var cover = provider("xy-screenshot", registry, ScreenshotService.class,
                        html -> html.getBytes(StandardCharsets.UTF_8));
                try {
                    await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
                        assertEquals("admin", users.require(request).getUserRole());
                        assertArrayEquals("快照".getBytes(StandardCharsets.UTF_8), screenshots.render("快照"));
                    });
                } finally {
                    cover.destroy();
                }
            } finally {
                next.destroy();
            }
        }
    }

    private <T> DubboBootstrap provider(String name, String address, Class<T> type, T impl) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        var application = new ApplicationConfig(name);
        application.setQosEnable(false);
        var registry = new RegistryConfig(address);
        registry.setRegisterMode("instance");
        registry.setUseAsConfigCenter(false);
        var protocol = new ProtocolConfig("tri", port);
        protocol.setHost("::ffff:127.0.0.1");
        var service = new ServiceConfig<T>();
        service.setInterface(type);
        service.setRef(impl);
        return DubboBootstrap.newInstance().application(application).registry(registry)
                .protocol(protocol).service(service).start();
    }
}
