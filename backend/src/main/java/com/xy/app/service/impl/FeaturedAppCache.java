package com.xy.app.service.impl;

import com.xy.app.model.vo.AppFeaturedVO;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// 首页只有一组公开卡片。读取和清除共用同一把锁，旧查询不会在清除后重新填入缓存。
final class FeaturedAppCache {

    private static final long TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private final LongSupplier nanoTime;
    private List<AppFeaturedVO> cards;
    private long loadedAt;

    FeaturedAppCache(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    synchronized List<AppFeaturedVO> get(Supplier<List<AppFeaturedVO>> query) {
        if (cards == null || nanoTime.getAsLong() - loadedAt >= TTL_NANOS) {
            cards = List.copyOf(query.get());
            loadedAt = nanoTime.getAsLong();
        }
        return cards;
    }

    synchronized void invalidate() {
        cards = null;
    }
}
