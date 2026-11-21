package com.xy.generation.service.impl;

import com.xy.common.ErrorCode;
import com.xy.exception.BusinessException;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Order(0) // 在数据库事务外包住建任务；提交失败也要归还本次名额。
public class GenerationStartLimiter {

    private static final Logger log = LoggerFactory.getLogger(GenerationStartLimiter.class);
    private final RedissonClient redis;

    public GenerationStartLimiter(RedissonClient redis) {
        this.redis = redis;
    }

    @Around("@annotation(limit) && args(*,userId,..)")
    public Object limit(ProceedingJoinPoint call, LimitGenerationStarts limit, long userId) throws Throwable {
        String endpoint = call.getSignature().getDeclaringTypeName() + "." + call.getSignature().getName();
        String key = switch (limit.scope()) {
            case ACCOUNT -> Long.toString(userId);
            case ENDPOINT -> "api:" + endpoint;
            case IP -> {
                var request = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                yield "ip:" + endpoint + ":" + request.getRequest().getRemoteAddr();
            }
        };
        String account = "xy:generation:starts:{" + key + "}";
        var lock = redis.getLock(account + ":lock");
        try {
            // 只串行同账号的建任务事务，离开这里之后才开始模型调用。
            lock.lock();
            try {
                RRateLimiter slot = reserve(account, limit);
                try {
                    return call.proceed();
                } catch (Throwable failure) {
                    try {
                        slot.delete();
                    } catch (RedisException disconnected) {
                        failure.addSuppressed(disconnected);
                    }
                    throw failure;
                }
            } finally {
                try {
                    lock.unlock();
                } catch (RedisException | IllegalMonitorStateException disconnected) {
                    // 已提交的任务仍须开流；断线留下的锁由 Redisson 自行到期。
                    log.warn("生成建任务锁未能释放");
                }
            }
        } catch (RedisException unavailable) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "生成限流服务暂不可用，请稍后重试");
        }
    }

    private RRateLimiter reserve(String account, LimitGenerationStarts limit) {
        Duration window = Duration.ofSeconds(limit.seconds());
        long waitMillis = window.toMillis();
        // 每个名额独立复原：建任务失败只退本次名额，不重置同账号已成功的请求。
        for (int i = 0; i < limit.permits(); i++) {
            var slot = redis.getRateLimiter(account + ":" + i);
            try {
                if (take(slot, window)) return slot;
            } catch (RedisException error) {
                // 检查与领取之间恰好过期时，在已持有的账号锁下重建该名额。
                if (slot.isExists()) throw error;
                if (take(slot, window)) return slot;
            }
            waitMillis = Math.min(waitMillis, slot.remainTimeToLive());
        }
        long seconds = Math.max(1, Math.ceilDiv(waitMillis, 1000));
        throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, limit.scope().label + " " + limit.seconds()
                + " 秒内最多发起 " + limit.permits() + " 次生成，请在 " + seconds + " 秒后重试");
    }

    private boolean take(RRateLimiter slot, Duration window) {
        boolean fresh = slot.trySetRate(RateType.OVERALL, 1, window, window);
        // 只对有额度的名额调用 tryAcquire，避免被拒的请求刷新空闲 TTL、延长等待提示。
        return (fresh || slot.availablePermits() > 0) && slot.tryAcquire();
    }
}
