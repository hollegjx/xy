package com.xy.user.controller;

import com.xy.app.service.AppService;
import com.xy.app.controller.AppController;
import com.xy.app.model.vo.AppFeaturedSlice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.exception.GlobalExceptionHandler;
import com.xy.exception.SessionAvailabilityFilter;
import com.xy.generation.GenerationTestRedis;
import com.xy.generation.controller.GenerationController;
import com.xy.generation.service.GenerationService;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.service.impl.UserDeletionPersistence;
import com.xy.user.service.impl.UserServiceImpl;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SharedLoginSessionTest {
    @Test
    void cookieFromFirstBackendWorksOnSecondAndLogoutRevokesItOnBoth(@TempDir Path dir) throws Exception {
        var redis = GenerationTestRedis.start(dir);
        try {
            var nodes = new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class, SessionAutoConfiguration.class))
                    .withPropertyValues("spring.data.redis.port=" + redis.ports().getFirst(), "spring.session.redis.namespace=xy:session");
            nodes.run(a -> nodes.run(b -> {
                var first = MockMvcBuilders.standaloneSetup(new UserController(members())).setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(a.getBean("springSessionRepositoryFilter", Filter.class)).build();
                var second = MockMvcBuilders.standaloneSetup(new UserController(members())).setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(b.getBean("springSessionRepositoryFilter", Filter.class)).build();
                var response = first.perform(post("/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"member\",\"userPassword\":\"test-password\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse();
                var cookie = response.getCookie("SESSION");
                assertThat(cookie).isNotNull();
                second.perform(get("/users/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("42"));
                second.perform(post("/users/logout").cookie(cookie)).andExpect(status().isOk());
                first.perform(get("/users/me").cookie(cookie)).andExpect(status().isUnauthorized());
            }));
        } finally {
            redis.stop();
        }
    }

    @Test
    void redisOutageBlocksPrivateGenerationButKeepsPublicFeaturedPagesReadable(@TempDir Path dir) throws Exception {
        var redis = GenerationTestRedis.start(dir);
        try {
            var users = members();
            var generator = mock(GenerationService.class);
            var apps = mock(AppService.class);
            when(apps.listFeaturedApps(1, 6)).thenReturn(new AppFeaturedSlice(List.of(), false));
            when(apps.getFeaturedCover(2L, 3L)).thenReturn(new byte[]{7});
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                            RedisAutoConfiguration.class, SessionAutoConfiguration.class))
                    .withPropertyValues("spring.data.redis.port=" + redis.ports().getFirst(),
                            "spring.data.redis.connect-timeout=400ms", "spring.data.redis.timeout=400ms")
                    .run(context -> {
                        var mvc = MockMvcBuilders.standaloneSetup(new UserController(users), new AppController(apps, users),
                                        new GenerationController(generator, users)).setControllerAdvice(new GlobalExceptionHandler())
                                .addFilters(new SessionAvailabilityFilter(new ObjectMapper()),
                                        context.getBean("springSessionRepositoryFilter", Filter.class)).build();
                        var cookie = mvc.perform(post("/users/login").contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"userAccount\":\"member\",\"userPassword\":\"test-password\"}"))
                                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
                        assertThat(cookie).isNotNull();
                        redis.stop();
                        mvc.perform(post("/apps/55/generations").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM).content("{\"prompt\":\"做一个网站\"}"))
                                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value(50300)).andExpect(jsonPath("$.message").value("会话服务暂时不可用，请稍后重试"));
                        verifyNoInteractions(generator);
                        mvc.perform(get("/api/apps/featured").contextPath("/api").cookie(cookie))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
                        mvc.perform(get("/api/apps/2/covers/3").contextPath("/api").cookie(cookie)).andExpect(status().isOk())
                                .andExpect(content().bytes(new byte[]{7}));
                        verify(apps).listFeaturedApps(1, 6);
                    });
        } finally {
            if (redis.isActive()) redis.stop();
        }
    }

    private static UserServiceImpl members() {
        var users = mock(UserMapper.class);
        var passwords = mock(PasswordEncoder.class);
        var user = new User();
        user.setId(42L);
        user.setUserAccount("member");
        user.setUserPassword("hash");
        user.setUserRole("user");
        when(users.selectOneByQuery(any())).thenReturn(user);
        when(users.selectOneById(42L)).thenReturn(user);
        when(passwords.matches("test-password", "hash")).thenReturn(true);
        return new UserServiceImpl(users, passwords, mock(AppService.class), mock(UserDeletionPersistence.class));
    }
}
