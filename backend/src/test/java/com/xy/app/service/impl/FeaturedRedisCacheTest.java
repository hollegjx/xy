package com.xy.app.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.xy.app.mapper.AppMapper;
import com.xy.app.model.dto.AppFeaturedRequest;
import com.xy.app.model.entity.App;
import com.xy.app.model.vo.AppFeaturedVO;
import com.xy.app.service.AppService;
import com.xy.chat.mapper.ChatHistoryMapper;
import com.xy.config.DeploymentConfiguration;
import com.xy.config.FeaturedCacheConfiguration;
import com.xy.manager.GeneratedFileManager;
import com.xy.user.model.entity.User;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeaturedRedisCacheTest {

    private static final String PUBLIC_ORIGIN = "https://sites.example.test";
    private final AppMapper mapper = mock(AppMapper.class);
    private final App work = new App();
    private ApplicationContextRunner contexts;
    private RedisServer redis;

    @BeforeEach
    void prepare(@TempDir Path directory) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().bind("127.0.0.1").port(port)
                .setting("dir \"" + directory + "\"").setting("save \"\"").setting("appendonly no").build();
        work.setId(55L);
        work.setUserId(7L);
        work.setAppName("公开作品");
        work.setInitPrompt("仅创建者可见");
        work.setCodeGenType("html");
        work.setDeployKey("public-work");
        work.setPublishedVersion(101L);
        work.setFeatured(true);
        when(mapper.selectListByQuery(any(QueryWrapper.class)))
                .thenAnswer(i -> work.getFeatured() ? List.of(work) : List.of());
        when(mapper.selectOneById(55L)).thenReturn(work);
        when(mapper.updateFeatured(55L, 7L, false)).thenAnswer(i -> { work.setFeatured(false); return 1; });
        contexts = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, CacheAutoConfiguration.class))
                .withUserConfiguration(FeaturedCacheConfiguration.class, FeaturedAppPages.class, AppServiceImpl.class,
                        DeploymentConfiguration.class)
                .withPropertyValues("spring.cache.type=redis", "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=" + port,
                        "spring.data.redis.connect-timeout=250ms", "spring.data.redis.timeout=250ms",
                        "xy.deployment.directory=" + directory, "xy.deployment.public-url=" + PUBLIC_ORIGIN)
                .withBean(AppMapper.class, () -> mapper).withBean(ChatHistoryMapper.class, () -> mock(ChatHistoryMapper.class))
                .withBean(GeneratedFileManager.class, () -> mock(GeneratedFileManager.class))
                .withBean(GenerationModeRouter.class, () -> mock(GenerationModeRouter.class));
        redis.start();
    }

    @AfterEach
    void stopRedis() throws Exception {
        if (redis != null && redis.isActive()) redis.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://new-sites.example.test:8443"})
    void restartWithNewPublicOriginDoesNotServeOldLinks(String newOrigin) {
        contexts.run(first -> {
            var page = first.getBean(AppService.class).listFeaturedApps(1, 6);
            assertThat(page.records()).extracting(AppFeaturedVO::deployUrl).containsExactly(PUBLIC_ORIGIN + "/public-work/");
            assertThat(first.getBean(StringRedisTemplate.class).keys("xy:featured:pages::*")).hasSize(1);
        });
        // 上一个后端已经关闭，保留同一份 Redis，再以不同站点配置启动。
        contexts.withPropertyValues("xy.deployment.public-url=" + newOrigin).run(restarted -> {
            var service = restarted.getBean(AppService.class);
            var page = service.listFeaturedApps(1, 6);
            if (newOrigin.isEmpty()) {
                assertThat(page.records()).isEmpty();
                assertThat(page.hasNext()).isFalse();
                assertThat(service.listFeaturedApps(11, 6)).isEqualTo(page);
                verify(mapper).selectListByQuery(any(QueryWrapper.class));
            } else {
                assertThat(page.records()).extracting(AppFeaturedVO::deployUrl).containsExactly(newOrigin + "/public-work/");
                assertThat(service.listFeaturedApps(1, 6)).isEqualTo(page);
                verify(mapper, times(2)).selectListByQuery(any(QueryWrapper.class));
            }
        });
    }

    @Test
    void twoBackendsShareOnlyHotPagesAndSeeTheSameWithdrawal() {
        contexts.run(first -> contexts.run(second -> {
            assertThat(first.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class);
            assertThat(second.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class);
            var a = first.getBean(AppService.class);
            var b = second.getBean(AppService.class);
            var store = first.getBean(StringRedisTemplate.class);
            var page = a.listFeaturedApps(1, 6);
            assertThat(page.records()).singleElement().satisfies(card -> assertThat(card.id()).isEqualTo("55"));
            assertThat(b.listFeaturedApps(1, 6)).isEqualTo(page);
            verify(mapper).selectListByQuery(any(QueryWrapper.class));
            var revision = store.opsForValue().get("xy:featured:revision::current");
            var pagePrefix = "xy:featured:pages::" + PUBLIC_ORIGIN + ":" + revision + ":";
            var key = pagePrefix + "1:6";
            assertThat(store.opsForValue().get(key)).contains("公开作品").doesNotContain("仅创建者可见");
            assertThat(store.getExpire(key)).isBetween(295L, 300L);
            store.expireAt(key, Instant.EPOCH);
            b.listFeaturedApps(1, 6);
            a.listFeaturedApps(1, 3);
            a.listFeaturedApps(10, 6);
            b.listFeaturedApps(10, 6);
            a.listFeaturedApps(11, 6);
            b.listFeaturedApps(11, 6);
            verify(mapper, times(6)).selectListByQuery(any(QueryWrapper.class));
            assertThat(store.hasKey(pagePrefix + "11:6")).isFalse();

            var admin = new User();
            admin.setUserRole("admin");
            b.updateFeaturedForAdmin(55L, new AppFeaturedRequest(false), admin);
            assertThat(a.listFeaturedApps(1, 6).records()).isEmpty();
            assertThat(b.listFeaturedApps(1, 6).records()).isEmpty();
            assertThat(a.listFeaturedApps(10, 6).records()).isEmpty();
            verify(mapper, times(8)).selectListByQuery(any(QueryWrapper.class));

            redis.stop();
            when(mapper.updateFeatured(55L, 7L, true)).thenAnswer(i -> { work.setFeatured(true); return 1; });
            assertThat(b.updateFeaturedForAdmin(55L, new AppFeaturedRequest(true), admin)).isTrue();
            assertThat(a.listFeaturedApps(1, 6)).isEqualTo(page);
        }));
    }
}
