package com.charphone.aigateway.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.charphone.aigateway.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API Key 限流 Filter：在 ApiKeyFilter 之後執行（@Order(2)）。
 * 超限回 429 + Retry-After header。
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitService rateLimitService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService, AppProperties props, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!props.getRateLimit().isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        Object attr = request.getAttribute(ApiKeyFilter.ATTR_CLIENT);
        if (attr == null) {
            // 未認證的請求（公開路徑），跳過限流
            chain.doFilter(request, response);
            return;
        }

        ApiClient client = (ApiClient) attr;
        int retryAfter = rateLimitService.checkLimit(client);
        if (retryAfter > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\","
                    + "\"message\":\"請求過於頻繁，請稍後再試\","
                    + "\"retryAfter\":" + retryAfter + "}");
            return;
        }

        chain.doFilter(request, response);

        // 請求完成後記錄（僅認證通過的請求）
        rateLimitService.recordRequest(client.id());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "/api/v1/health".equals(uri) || "/test.html".equals(uri) || "/admin.html".equals(uri)
                || uri.startsWith("/error") || uri.startsWith("/static/");
    }
}
