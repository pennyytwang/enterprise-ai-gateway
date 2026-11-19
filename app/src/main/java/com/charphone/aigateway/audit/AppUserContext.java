package com.charphone.aigateway.audit;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

/**
 * CharPhone 代理注入的使用者身分，用於 audit 關聯。
 *
 * <p>這些 header 只由 CharPhone 的 ApiProxyServlet 覆寫；直接呼叫 gateway
 * 的用戶端沒有此上下文，欄位維持 null。</p>
 */
public record AppUserContext(Integer userId, String username, String sessionId) {

    public static final AppUserContext EMPTY = new AppUserContext(null, null, null);

    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_SESSION_ID_LENGTH = 64;

    /**
     * 從 request header 取得 CharPhone 使用者上下文。
     *
     * @param request HTTP request，可為 null
     * @return 使用者上下文；缺少 header 時回傳 {@link #EMPTY}
     */
    public static AppUserContext from(HttpServletRequest request) {
        if (request == null) {
            return EMPTY;
        }
        return new AppUserContext(
                parseUserId(request.getHeader("X-CharPhone-User-Id")),
                parseUsername(request.getHeader("X-CharPhone-Username")),
                truncate(request.getHeader("X-CharPhone-Session-Id"), MAX_SESSION_ID_LENGTH));
    }

    private static Integer parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int id = Integer.parseInt(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseUsername(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(value.trim(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = value.trim();
        }
        return truncate(decoded, MAX_USERNAME_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
