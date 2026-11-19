package com.charphone.aigateway.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.ModelRouter;

/**
 * Health check 增強：DB、Solr、Model Router 狀態。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties props;
    private final LlmProvider provider;
    private final long startTime = System.currentTimeMillis();

    public HealthController(@Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate,
            AppProperties props, LlmProvider provider) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.provider = provider;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "ai-gateway");
        result.put("status", "UP");

        // DB check
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            db.put("status", "UP");
        } catch (Exception e) {
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
            result.put("status", "DEGRADED");
        }
        result.put("db", db);

        // Model info
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("active", props.getActiveModel());
        model.put("provider", props.getProvider());
        if (provider instanceof ModelRouter router) {
            model.put("router", "active");
            model.put("stats", router.getStats());
        } else {
            model.put("router", "single-provider");
        }
        result.put("model", model);

        // Uptime
        long uptimeMs = System.currentTimeMillis() - startTime;
        long hours = uptimeMs / 3_600_000;
        long minutes = (uptimeMs % 3_600_000) / 60_000;
        result.put("uptime", hours + "h " + minutes + "m");

        // Rate limit status
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("enabled", props.getRateLimit().isEnabled());
        rateLimit.put("defaultDaily", props.getRateLimit().getDefaultDaily());
        rateLimit.put("defaultPerMin", props.getRateLimit().getDefaultPerMin());
        result.put("rateLimit", rateLimit);

        return result;
    }
}
