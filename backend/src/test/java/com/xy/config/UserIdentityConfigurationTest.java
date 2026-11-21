package com.xy.config;

import com.xy.app.controller.AppController;
import com.xy.app.model.vo.AppFeaturedSlice;
import com.xy.app.service.AppService;
import com.xy.client.UserIdentityLookup;
import com.xy.common.UserConstant;
import com.xy.exception.GlobalExceptionHandler;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.dto.UserIdentity;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.config.*;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserIdentityConfigurationTest {
    @Test
    void remoteRoleComesFromTheSessionAccountAndDeletionRevokesIt() throws Exception {
        int port = freePort();
        var role = new AtomicReference<>("admin");
        var provider = new ServiceConfig<UserIdentityLookup>();
        provider.setInterface(UserIdentityLookup.class);
        provider.setRef(id -> id == 42L && role.get() != null ? new UserIdentity(id, role.get()) : null);
        var application = new ApplicationConfig("test-user-provider");
        application.setQosEnable(false);
        var protocol = new ProtocolConfig("tri", port);
        protocol.setHost("::ffff:127.0.0.1");
        var server = DubboBootstrap.newInstance().application(application).registry(new RegistryConfig("N/A"))
                .protocol(protocol).service(provider).start();
        try {
            var apps = mock(AppService.class);
            platform(port, apps).run(ctx -> {
                var mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(AppController.class))
                        .setControllerAdvice(new GlobalExceptionHandler()).build();
                var session = loginSession();
                mvc.perform(get("/apps/55").session(session).param("userId", "99").param("userRole", "user"))
                        .andExpect(status().isOk());
                verify(apps).getAppById(eq(55L), argThat(user -> user.getId() == 42L && "admin".equals(user.getUserRole())));
                role.set("user");
                mvc.perform(get("/apps/55").session(session)).andExpect(status().isOk());
                verify(apps).getAppById(eq(55L), argThat(user -> user.getId() == 42L && "user".equals(user.getUserRole())));
                role.set(null);
                mvc.perform(get("/apps/55").session(session)).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value(40100));
                assertThat(session.getAttribute(UserConstant.LOGIN_USER_ID)).isNull();
                verifyNoMoreInteractions(apps);
            });
        } finally {
            server.destroy();
        }
    }

    @Test
    void offlineUserServiceKeepsSessionReturns503WithoutLocalFallbackAndLeavesFeaturedPublic() throws Exception {
        var apps = mock(AppService.class);
        var local = mock(UserMapper.class);
        when(apps.listFeaturedApps(1, 6)).thenReturn(new AppFeaturedSlice(List.of(), false));
        platform(freePort(), apps).withBean(UserMapper.class, () -> local).run(ctx -> {
            var mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(AppController.class))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            var session = loginSession();
            mvc.perform(get("/apps/55").session(session)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(50300));
            assertThat(session.getAttribute(UserConstant.LOGIN_USER_ID)).isEqualTo(42L);
            mvc.perform(get("/apps/55")).andExpect(status().isUnauthorized());
            mvc.perform(get("/apps/featured").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
            verify(apps).listFeaturedApps(1, 6);
            verifyNoMoreInteractions(apps);
            verifyNoInteractions(local);
        });
    }

    private ApplicationContextRunner platform(int port, AppService apps) {
        return new ApplicationContextRunner().withUserConfiguration(UserIdentityConfiguration.class, AppController.class)
                .withBean(AppService.class, () -> apps)
                .withPropertyValues("xy.user.rpc-url=tri://127.0.0.1:" + port);
    }

    private MockHttpSession loginSession() {
        var session = new MockHttpSession();
        session.setAttribute(UserConstant.LOGIN_USER_ID, 42L);
        return session;
    }

    private int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
}
