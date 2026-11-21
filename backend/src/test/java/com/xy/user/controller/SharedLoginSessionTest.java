package com.xy.user.controller;

import com.xy.app.service.AppService;
import com.xy.exception.GlobalExceptionHandler;
import com.xy.generation.GenerationTestRedis;
import com.xy.user.mapper.UserMapper;
import com.xy.user.model.entity.User;
import com.xy.user.service.impl.UserDeletionPersistence;
import com.xy.user.service.impl.UserServiceImpl;
import jakarta.servlet.Filter;
import java.nio.file.Path;
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
            nodes.run(a -> nodes.run(b -> {
                java.util.function.Supplier<UserController> controller = () -> new UserController(new UserServiceImpl(users, passwords, mock(AppService.class), mock(UserDeletionPersistence.class)));
                var first = MockMvcBuilders.standaloneSetup(controller.get()).setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(a.getBean("springSessionRepositoryFilter", Filter.class)).build();
                var second = MockMvcBuilders.standaloneSetup(controller.get()).setControllerAdvice(new GlobalExceptionHandler())
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
}
