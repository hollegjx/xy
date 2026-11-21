package com.xy.generation.service.impl;

import com.xy.config.GenerationRedisConfiguration;
import com.xy.exception.BusinessException;
import com.xy.generation.GenerationTestRedis;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import redis.embedded.RedisServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class GenerationStartLimiterTest {
    private ApplicationContextRunner backend;
    private RedisServer redis;

    @BeforeEach
    void startRedis(@TempDir Path dir) throws Exception {
        redis = GenerationTestRedis.start(dir);
        backend = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, AopAutoConfiguration.class))
                .withUserConfiguration(GenerationRedisConfiguration.class, GenerationStartLimiter.class, Task.class)
                .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=" + redis.ports().getFirst(),
                        "spring.data.redis.database=9", "spring.data.redis.connect-timeout=200ms", "spring.data.redis.timeout=1s");
    }

    @AfterEach
    void stopRedis() throws Exception {
        if (redis.isActive()) redis.stop();
    }

    @Test
    void independentBackendsRaceForOneRemainingAccountPermit() {
        backend.run(first -> backend.run(second -> {
            var a = first.getBean(Task.class);
            var b = second.getBean(Task.class);
            assertThat(first.getBean(RedissonClient.class)).isNotSameAs(second.getBean(RedissonClient.class));
            for (int i = 0; i < 4; i++) (i % 2 == 0 ? a : b).start(i, 7);
            var gate = new CountDownLatch(1);
            var left = CompletableFuture.supplyAsync(() -> attempt(a, gate));
            var right = CompletableFuture.supplyAsync(() -> attempt(b, gate));
            gate.countDown();
            assertThat(java.util.List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 42900);
            assertThat(b.start(11, 8)).isEqualTo(11);
        }));
    }

    private int attempt(Task task, CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("未能同时发起请求");
            task.start(10, 7);
            return 0;
        } catch (BusinessException failure) {
            return failure.getCode();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void refundAndRepeatedDenialsDoNotMoveOlderStarts() {
        backend.run(first -> backend.run(second -> {
            var a = first.getBean(Task.class);
            var b = second.getBean(Task.class);
            a.shortWindow(11, 7, false);
            var oldest = first.getBean(RedissonClient.class).getRateLimiter("xy:generation:starts:{7}:0");
            await().atMost(Duration.ofSeconds(5)).until(() -> oldest.remainTimeToLive() <= 1800);
            assertThatThrownBy(() -> b.shortWindow(12, 7, true)).isInstanceOf(IllegalStateException.class);
            b.shortWindow(12, 7, false);
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> a.shortWindow(13, 7, false)).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).matches("每个账号 4 秒内最多发起 2 次生成，请在 [12] 秒后重试"));
            }
            await().atMost(Duration.ofSeconds(3)).until(() -> !oldest.isExists());
            a.shortWindow(13, 7, false);
            assertThatThrownBy(() -> b.shortWindow(14, 7, false)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo(42900));
        }));
    }

    @Test
    void redisOutageRejectsTheStartBeforeCallingTheTask() throws Exception {
        redis.stop();
        backend.run(ctx -> assertThatThrownBy(() -> ctx.getBean(Task.class).shortWindow(12, 7, true))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(50300)));
    }

    @Test
    void endpointAndIpScopesCanSharePermitsAcrossAccounts() {
        backend.run(ctx -> {
            var task = ctx.getBean(Task.class);
            task.byEndpoint(1, 7);
            assertThatThrownBy(() -> task.byEndpoint(2, 8)).isInstanceOf(BusinessException.class);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            var context = new org.springframework.web.context.request.ServletRequestAttributes(request);
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(context);
            try {
                request.setRemoteAddr("192.0.2.1");
                task.byIp(1, 7);
                assertThatThrownBy(() -> task.byIp(2, 8)).isInstanceOf(BusinessException.class);
                request.setRemoteAddr("192.0.2.2");
                task.byIp(2, 8);
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        });
    }

    static class Task {
        @LimitGenerationStarts(scope = LimitGenerationStarts.Scope.ENDPOINT, permits = 1, seconds = 60)
        public void byEndpoint(long app, long user) {}
        @LimitGenerationStarts(scope = LimitGenerationStarts.Scope.IP, permits = 1, seconds = 60)
        public void byIp(long app, long user) {}

        @LimitGenerationStarts(permits = 5, seconds = 60)
        public long start(long appId, long userId) {
            return appId;
        }

        @LimitGenerationStarts(permits = 2, seconds = 4)
        public void shortWindow(long appId, long userId, boolean fail) {
            if (fail) throw new IllegalStateException("建任务失败");
        }
    }
}
