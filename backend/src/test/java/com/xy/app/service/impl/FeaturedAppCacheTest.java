package com.xy.app.service.impl;

import com.xy.app.model.vo.AppFeaturedVO;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeaturedAppCacheTest {

    @Test
    void readingDoesNotExtendFiveMinuteLifetimeAndAFailedRefreshCanBeRetried() {
        var clock = new AtomicLong();
        var cache = new FeaturedAppCache(clock::get);
        var old = new AppFeaturedVO("1", "旧作品", "html", "https://sites.example.test/one/", null);
        assertThat(cache.get(() -> List.of(old))).containsExactly(old);

        clock.set(Duration.ofMinutes(5).toNanos() - 1);
        assertThat(cache.get(List::of)).containsExactly(old);
        clock.incrementAndGet();
        assertThatThrownBy(() -> cache.get(() -> { throw new IllegalStateException("查询暂不可用"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(List::of)).isEmpty();
    }
}
