package com.xy.generation.service.impl;

import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class GenerationStartLimiter {

    private static final int MAX_STARTS = 5;
    private static final long WINDOW = Duration.ofSeconds(60).toNanos();
    private final LongSupplier nanoTime;
    private final Map<Long, ArrayDeque<Long>> startsByUser = new HashMap<>();
    private long lastCleanup;

    public GenerationStartLimiter() {
        this(System::nanoTime);
    }

    public GenerationStartLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastCleanup = nanoTime.getAsLong();
    }

    long start(long userId, LongSupplier createGeneration) {
        long startTime = reserve(userId);
        try {
            // 先占名额；建任务要写数据库，不让其他账号等这次写入。
            return createGeneration.getAsLong();
        } catch (RuntimeException error) {
            refund(userId, startTime);
            throw error;
        }
    }

    private synchronized long reserve(long userId) {
        long now = nanoTime.getAsLong();
        if (now - lastCleanup >= WINDOW) {
            startsByUser.values().removeIf(starts -> now - starts.getLast() >= WINDOW);
            lastCleanup = now;
        }
        var starts = startsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        while (!starts.isEmpty() && now - starts.getFirst() >= WINDOW) starts.removeFirst();
        if (starts.size() >= MAX_STARTS) {
            long seconds = Math.ceilDiv(WINDOW - (now - starts.getFirst()), Duration.ofSeconds(1).toNanos());
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "每个账号 60 秒内最多发起 5 次生成，请在 " + seconds + " 秒后重试");
        }
        starts.addLast(now);
        return now;
    }

    private synchronized void refund(long userId, long startTime) {
        var starts = startsByUser.get(userId);
        if (starts != null) {
            starts.removeFirstOccurrence(startTime);
            if (starts.isEmpty()) startsByUser.remove(userId);
        }
    }
}
