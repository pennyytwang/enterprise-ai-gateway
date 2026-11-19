package com.charphone.aigateway.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用量報表：從 audit_log 彙總各模型/供應商的使用量、延遲、成功率。
 */
@Service
public class UsageReportService {

    private final JdbcTemplate jdbcTemplate;

    public UsageReportService(@Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 依模型彙總用量（含平均延遲、成功率）。 */
    public Map<String, Object> reportByModel() {
        String sql = """
                SELECT model,
                       COUNT(*) AS total_requests,
                       SUM(CASE WHEN status_code = 200 THEN 1 ELSE 0 END) AS success_count,
                       SUM(prompt_tokens) AS total_prompt_tokens,
                       SUM(completion_tokens) AS total_completion_tokens,
                       SUM(prompt_tokens + completion_tokens) AS total_tokens,
                       ROUND(AVG(latency_ms)) AS avg_latency_ms,
                       MIN(created_at) AS first_used,
                       MAX(created_at) AS last_used
                FROM audit_log
                GROUP BY model
                ORDER BY total_requests DESC
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", row.get("model"));
            m.put("totalRequests", row.get("total_requests"));
            m.put("successCount", row.get("success_count"));
            int total = ((Number) row.get("total_requests")).intValue();
            int success = ((Number) row.get("success_count")).intValue();
            m.put("successRate", total > 0 ? Math.round(success * 100.0 / total) : 0);
            m.put("totalPromptTokens", row.get("total_prompt_tokens"));
            m.put("totalCompletionTokens", row.get("total_completion_tokens"));
            m.put("totalTokens", row.get("total_tokens"));
            m.put("avgLatencyMs", row.get("avg_latency_ms"));
            m.put("firstUsed", String.valueOf(row.get("first_used")));
            m.put("lastUsed", String.valueOf(row.get("last_used")));
            models.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);

        Map<String, Object> totals = new LinkedHashMap<>();
        List<Map<String, Object>> totalRow = jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS total, SUM(prompt_tokens) AS pt, SUM(completion_tokens) AS ct, "
                        + "ROUND(AVG(latency_ms)) AS avg_latency FROM audit_log");
        if (!totalRow.isEmpty()) {
            Map<String, Object> t = totalRow.get(0);
            totals.put("totalRequests", t.get("total"));
            totals.put("totalPromptTokens", t.get("pt"));
            totals.put("totalCompletionTokens", t.get("ct"));
            totals.put("avgLatencyMs", t.get("avg_latency"));
        }
        result.put("totals", totals);
        return result;
    }

    /** 依日彙總用量（最近 N 天）。 */
    public Map<String, Object> reportByDay(int days) {
        String sql = """
                SELECT DATE(created_at) AS day,
                       COUNT(*) AS total_requests,
                       SUM(prompt_tokens) AS total_prompt_tokens,
                       SUM(completion_tokens) AS total_completion_tokens,
                       ROUND(AVG(latency_ms)) AS avg_latency_ms
                FROM audit_log
                WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                GROUP BY DATE(created_at)
                ORDER BY day DESC
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, days);
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", String.valueOf(row.get("day")));
            m.put("totalRequests", row.get("total_requests"));
            m.put("totalPromptTokens", row.get("total_prompt_tokens"));
            m.put("totalCompletionTokens", row.get("total_completion_tokens"));
            m.put("avgLatencyMs", row.get("avg_latency_ms"));
            daily.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("daily", daily);
        return result;
    }
}
