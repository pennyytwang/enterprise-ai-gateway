package com.charphone.aigateway.security;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.charphone.aigateway.config.AppProperties;

/**
 * API Key 限流服務：per-min 滑動視窗（記憶體）+ daily 配額（DB cache）。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final JdbcTemplate jdbcTemplate;
    private final int defaultDaily;
    private final int defaultPerMin;

    /** clientId → 時間戳佇列（per-min 滑動視窗） */
    private final Map<Integer, Deque<Long>> minuteWindows = new ConcurrentHashMap<>();

    /** clientId → {dailyCount, day, cachedLimit, lastCheck} */
    private final Map<Integer, DailyQuota> dailyQuotas = new ConcurrentHashMap<>();

    public RateLimitService(@Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate,
            AppProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultDaily = props.getRateLimit().getDefaultDaily();
        this.defaultPerMin = props.getRateLimit().getDefaultPerMin();
    }

    /**
     * 檢查是否超限。回傳 null 表示通過，否則回傳 Retry-After 秒數。
     */
    public int checkLimit(ApiClient client) {
        int effectivePerMin = client.rateLimitPerMin() > 0 ? client.rateLimitPerMin() : defaultPerMin;
        int effectiveDaily = client.rateLimitDaily() > 0 ? client.rateLimitDaily() : defaultDaily;

        if (effectivePerMin > 0) {
            int retryAfter = checkPerMinute(client.id(), effectivePerMin);
            if (retryAfter > 0) return retryAfter;
        }

        if (effectiveDaily > 0) {
            int retryAfter = checkDaily(client.id(), effectiveDaily);
            if (retryAfter > 0) return retryAfter;
        }

        return 0;
    }

    /** 記錄一次請求（在請求完成後呼叫）。 */
    public void recordRequest(int clientId) {
        long now = System.currentTimeMillis();
        minuteWindows.computeIfAbsent(clientId, k -> new ConcurrentLinkedDeque<>()).addLast(now);
        dailyQuotas.computeIfAbsent(clientId, k -> new DailyQuota()).count.incrementAndGet();
    }

    private int checkPerMinute(int clientId, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;
        Deque<Long> window = minuteWindows.computeIfAbsent(clientId, k -> new ConcurrentLinkedDeque<>());

        // 清理過期
        while (!window.isEmpty() && window.peekFirst() < windowStart) {
            window.pollFirst();
        }

        if (window.size() >= limit) {
            long oldest = window.peekFirst();
            int retryAfter = (int) ((oldest + 60_000 - now) / 1000) + 1;
            log.warn("Rate limit exceeded: clientId={}, perMin={}/{}", clientId, window.size(), limit);
            return Math.max(retryAfter, 1);
        }
        return 0;
    }

    private int checkDaily(int clientId, int limit) {
        long now = System.currentTimeMillis();
        DailyQuota quota = dailyQuotas.computeIfAbsent(clientId, k -> new DailyQuota());

        String today = java.time.LocalDate.now().toString();
        if (!today.equals(quota.day)) {
            // 新的一天，重新查詢 DB
            quota.day = today;
            Integer dbCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE client_id = ? AND DATE(created_at) = CURDATE()",
                    Integer.class, clientId);
            quota.count.set(dbCount != null ? dbCount : 0);
            quota.cachedLimit = limit;
        }

        if (quota.count.get() >= limit) {
            log.warn("Daily rate limit exceeded: clientId={}, daily={}/{}", clientId, quota.count.get(), limit);
            return 3600; // 重試等 1 小時（每日配額用完）
        }
        return 0;
    }

    private static class DailyQuota {
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
        volatile String day = "";
        volatile int cachedLimit = 0;
    }
}
