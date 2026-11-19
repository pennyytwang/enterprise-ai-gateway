package com.charphone.aigateway.audit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.security.SensitiveDataMasker;

/**
 * 稽核日誌寫入（aigateway DB）。所有 request（含 prompt/response、用量、延遲）落 audit_log。
 * 敏感資料（身分證/銀行帳號/電話）寫入前自動遮罩。
 */
@Repository
public class AuditLogDao {

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties props;

    public AuditLogDao(@Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate, AppProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    public void record(AuditRecord record) {
        if (!props.getAudit().isEnabled()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO audit_log (request_id, client_id, client_name, app_user_id, app_username, "
                            + "app_session_id, endpoint, model, stream, prompt, response, prompt_tokens, "
                            + "completion_tokens, latency_ms, status_code) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    record.requestId(),
                    record.clientId(),
                    record.clientName(),
                    record.appUserId(),
                    record.appUsername(),
                    record.appSessionId(),
                    record.endpoint(),
                    record.model(),
                    record.stream() ? 1 : 0,
                    truncate(SensitiveDataMasker.mask(record.prompt()), 20000),
                    truncate(SensitiveDataMasker.mask(record.response()), 50000),
                    record.promptTokens(),
                    record.completionTokens(),
                    record.latencyMs(),
                    record.statusCode());
        } catch (Exception e) {
            // 稽核失敗不應影響主流程，僅記錄
            org.slf4j.LoggerFactory.getLogger(AuditLogDao.class).warn("稽核日誌寫入失敗: {}", e.getMessage());
        }
    }

    public void recordToolCall(AuditRecord record) {
        if (!props.getAudit().isEnabled()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO tool_audit_log (request_id, client_id, client_name, app_user_id, app_username, "
                            + "app_session_id, endpoint, model, prompt, response, prompt_tokens, "
                            + "completion_tokens, latency_ms, status_code) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    record.requestId(),
                    record.clientId(),
                    record.clientName(),
                    record.appUserId(),
                    record.appUsername(),
                    record.appSessionId(),
                    record.endpoint(),
                    record.model(),
                    truncate(SensitiveDataMasker.mask(record.prompt()), 20000),
                    truncate(SensitiveDataMasker.mask(record.response()), 2000),
                    record.promptTokens(),
                    record.completionTokens(),
                    record.latencyMs(),
                    record.statusCode());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuditLogDao.class).warn("工具稽核寫入失敗: {}", e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record AuditRecord(String requestId, int clientId, String clientName, Integer appUserId,
            String appUsername, String appSessionId, String endpoint, String model,
            boolean stream, String prompt, String response, int promptTokens, int completionTokens, long latencyMs,
            int statusCode) {
    }
}
