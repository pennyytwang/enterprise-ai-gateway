package com.charphone.aigateway.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API Key 認證：讀取 X-API-Key，SHA-256 雜湊後查 api_client 表。
 * 通過後把 ApiClient 放入 request attribute 供下游使用。
 */
@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ATTR_CLIENT = "gateway.apiClient";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final ApiClientDao apiClientDao;

    public ApiKeyFilter(ApiClientDao apiClientDao) {
        this.apiClientDao = apiClientDao;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isPublicPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            reject(response, 401, "MISSING_API_KEY", "缺少 X-API-Key header");
            return;
        }

        Optional<ApiClient> client = apiClientDao.findByKeyHash(sha256(apiKey));
        if (client.isEmpty() || !client.get().enabled()) {
            reject(response, 401, "INVALID_API_KEY", "API Key 無效或已停用");
            return;
        }

        request.setAttribute(ATTR_CLIENT, client.get());
        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String uri) {
        return "/api/v1/health".equals(uri)
                || "/test.html".equals(uri)
                || "/admin.html".equals(uri)
                || uri.startsWith("/error");
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
