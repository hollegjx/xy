package com.xy.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xy.common.ApiResponse;
import com.xy.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
public class SessionAvailabilityFilter extends OncePerRequestFilter {
    private static final Pattern PUBLIC_READ = Pattern.compile("/health|/apps/featured|/apps/[0-9]+/covers/[0-9]+");
    static final String UNAVAILABLE = "会话服务暂时不可用，请稍后重试";
    private final ObjectMapper mapper;

    public SessionAvailabilityFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) && PUBLIC_READ.matcher(path).matches()) {
            // 公开响应对所有账号相同；即使浏览器带着旧 Cookie，也不需要去 Redis 取会话。
            request = new HttpServletRequestWrapper(request) {
                @Override public Cookie[] getCookies() { return null; }
            };
        }
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException error) {
            // Spring Session 读写也可能发生在 MVC 异常处理器之外。
            if (response.isCommitted() || !redisUnavailable(error)) throw error;
            response.reset();
            var code = ErrorCode.SERVICE_UNAVAILABLE;
            response.setStatus(code.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.failure(code.getCode(), UNAVAILABLE));
        }
    }

    static boolean redisUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException) return true;
        }
        return false;
    }
}
