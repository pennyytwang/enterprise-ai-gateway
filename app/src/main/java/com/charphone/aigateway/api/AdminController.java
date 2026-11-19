package com.charphone.aigateway.api;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.ModelRouter;
import com.charphone.aigateway.rag.IngestionService;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.security.ApiClientDao;
import com.charphone.aigateway.security.ApiKeyFilter;
import com.charphone.aigateway.security.RateLimitService;
import com.charphone.aigateway.settings.GatewaySettingsService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管理後台 API：API Key 管理、稽核紀錄查詢、系統狀態、RAG 文件管理。
 * 所有端點需 ADMIN 角色。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ApiClientDao apiClientDao;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties props;
    private final LlmProvider provider;
    private final IngestionService ingestionService;
    private final RateLimitService rateLimitService;
    private final GatewaySettingsService settingsService;

    public AdminController(ApiClientDao apiClientDao,
            @Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate,
            AppProperties props, LlmProvider provider,
            IngestionService ingestionService, RateLimitService rateLimitService,
            GatewaySettingsService settingsService) {
        this.apiClientDao = apiClientDao;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.provider = provider;
        this.ingestionService = ingestionService;
        this.rateLimitService = rateLimitService;
        this.settingsService = settingsService;
    }

    // ─── API Key 管理 ───

    @GetMapping("/keys")
    public Map<String, Object> listKeys(HttpServletRequest request) {
        requireAdmin(request);
        List<ApiClient> clients = apiClientDao.findAll();
        List<Map<String, Object>> keys = new java.util.ArrayList<>();
        for (ApiClient c : clients) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("clientName", c.clientName());
            m.put("keyPreview", c.apiKeyHash().substring(0, 8) + "****");
            m.put("role", c.role());
            m.put("enabled", c.enabled());
            m.put("rateLimitDaily", c.rateLimitDaily());
            m.put("rateLimitPerMin", c.rateLimitPerMin());

            // 查今日使用量
            Integer todayCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE client_id = ? AND DATE(created_at) = CURDATE()",
                    Integer.class, c.id());
            m.put("todayRequests", todayCount != null ? todayCount : 0);

            keys.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keys", keys);
        return result;
    }

    public record CreateKeyRequest(String clientName, String role, Integer rateLimitDaily, Integer rateLimitPerMin) {
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(@RequestBody CreateKeyRequest req, HttpServletRequest request) {
        requireAdmin(request);
        if (req.clientName() == null || req.clientName().isBlank()) {
            throw new IllegalArgumentException("clientName 不可為空");
        }

        // 生成 API Key
        String plainKey = generateApiKey();
        String hash = ApiKeyFilter.sha256(plainKey);
        String role = req.role() != null ? req.role() : "USER";
        int daily = req.rateLimitDaily() != null ? req.rateLimitDaily() : 0;
        int perMin = req.rateLimitPerMin() != null ? req.rateLimitPerMin() : 0;

        int id = apiClientDao.insert(req.clientName(), hash, role, daily, perMin);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("clientName", req.clientName());
        result.put("apiKey", plainKey);
        result.put("role", role);
        result.put("rateLimitDaily", daily);
        result.put("rateLimitPerMin", perMin);
        result.put("warning", "API Key 僅顯示一次，請妥善保存");
        return result;
    }

    public record UpdateKeyRequest(Boolean enabled, Integer rateLimitDaily, Integer rateLimitPerMin) {
    }

    @PatchMapping("/keys/{id}")
    public Map<String, Object> updateKey(@PathVariable int id, @RequestBody UpdateKeyRequest req,
            HttpServletRequest request) {
        requireAdmin(request);
        apiClientDao.update(id, req.enabled(), req.rateLimitDaily(), req.rateLimitPerMin());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("updated", true);
        return result;
    }

    @DeleteMapping("/keys/{id}")
    public Map<String, Object> deleteKey(@PathVariable int id, HttpServletRequest request) {
        requireAdmin(request);
        apiClientDao.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("deleted", true);
        return result;
    }

    // ─── 稽核紀錄 ───

    @GetMapping("/audit")
    public Map<String, Object> queryAudit(
            @RequestParam(required = false) Integer clientId,
            @RequestParam(required = false) String appUsername,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        requireAdmin(request);

        StringBuilder where = new StringBuilder("WHERE 1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();

        if (clientId != null) {
            where.append(" AND client_id = ?");
            params.add(clientId);
        }
        if (appUsername != null && !appUsername.isBlank()) {
            where.append(" AND (app_username LIKE ? OR CAST(app_user_id AS CHAR) = ?)");
            params.add("%" + appUsername.trim() + "%");
            params.add(appUsername.trim());
        }
        if (model != null && !model.isBlank()) {
            where.append(" AND model LIKE ?");
            params.add("%" + model + "%");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND DATE(created_at) >= ?");
            params.add(dateFrom);
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND DATE(created_at) <= ?");
            params.add(dateTo);
        }

        // 總數
        String countSql = "SELECT COUNT(*) FROM audit_log " + where;
        int total = jdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());

        // 分頁資料
        String dataSql = "SELECT id, request_id, client_id, client_name, app_user_id, app_username, "
                + "endpoint, model, prompt_tokens, completion_tokens, latency_ms, status_code, created_at "
                + "FROM audit_log " + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(size);
        params.add(page * size);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, params.toArray());
        List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("requestId", row.get("request_id"));
            m.put("clientId", row.get("client_id"));
            m.put("clientName", row.get("client_name"));
            m.put("appUserId", row.get("app_user_id"));
            m.put("appUsername", row.get("app_username"));
            m.put("endpoint", row.get("endpoint"));
            m.put("model", row.get("model"));
            m.put("promptTokens", row.get("prompt_tokens"));
            m.put("completionTokens", row.get("completion_tokens"));
            m.put("latencyMs", row.get("latency_ms"));
            m.put("statusCode", row.get("status_code"));
            m.put("createdAt", String.valueOf(row.get("created_at")));
            records.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("records", records);
        return result;
    }

    @GetMapping("/audit/{id}")
    public ResponseEntity<Map<String, Object>> auditDetail(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, request_id, client_id, client_name, app_user_id, app_username, app_session_id, "
                        + "endpoint, model, stream, prompt, response, prompt_tokens, completion_tokens, "
                        + "latency_ms, status_code, created_at FROM audit_log WHERE id = ?",
                id);
        if (rows.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NOT_FOUND");
            error.put("message", "找不到稽核紀錄");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        Map<String, Object> row = rows.get(0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", row.get("id"));
        detail.put("requestId", row.get("request_id"));
        detail.put("clientId", row.get("client_id"));
        detail.put("clientName", row.get("client_name"));
        detail.put("appUserId", row.get("app_user_id"));
        detail.put("appUsername", row.get("app_username"));
        detail.put("appSessionId", row.get("app_session_id"));
        detail.put("endpoint", row.get("endpoint"));
        detail.put("model", row.get("model"));
        detail.put("stream", row.get("stream"));
        detail.put("prompt", row.get("prompt"));
        detail.put("response", row.get("response"));
        detail.put("promptTokens", row.get("prompt_tokens"));
        detail.put("completionTokens", row.get("completion_tokens"));
        detail.put("latencyMs", row.get("latency_ms"));
        detail.put("statusCode", row.get("status_code"));
        detail.put("createdAt", String.valueOf(row.get("created_at")));

        List<Map<String, Object>> toolRows = jdbcTemplate.queryForList(
                "SELECT id, endpoint, prompt, response, latency_ms, status_code, created_at "
                        + "FROM tool_audit_log WHERE request_id = ? ORDER BY id",
                row.get("request_id"));
        List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
        for (Map<String, Object> toolRow : toolRows) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("id", toolRow.get("id"));
            tool.put("endpoint", toolRow.get("endpoint"));
            tool.put("args", toolRow.get("prompt"));
            tool.put("result", toolRow.get("response"));
            tool.put("latencyMs", toolRow.get("latency_ms"));
            tool.put("statusCode", toolRow.get("status_code"));
            tool.put("createdAt", String.valueOf(toolRow.get("created_at")));
            toolCalls.add(tool);
        }
        detail.put("toolCalls", toolCalls);

        return ResponseEntity.ok(detail);
    }

    // ─── 系統狀態 ───

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> result = new LinkedHashMap<>();

        // DB
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            Integer clientCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM api_client", Integer.class);
            Integer auditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
            Integer toolAuditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tool_audit_log", Integer.class);
            db.put("status", "UP");
            db.put("apiClients", clientCount);
            db.put("auditLogRecords", auditCount);
            db.put("toolAuditRecords", toolAuditCount);
        } catch (Exception e) {
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
        }
        result.put("db", db);

        // Model
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

        // Rate limit
        Map<String, Object> rl = new LinkedHashMap<>();
        rl.put("enabled", props.getRateLimit().isEnabled());
        rl.put("defaultDaily", props.getRateLimit().getDefaultDaily());
        rl.put("defaultPerMin", props.getRateLimit().getDefaultPerMin());
        result.put("rateLimit", rl);

        return result;
    }

    // ─── RAG 文件管理 ───

    @GetMapping("/rag/docs")
    public Map<String, Object> listRagDocs(HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", ingestionService.listDocuments());
        return result;
    }

    @DeleteMapping("/rag/docs/{docId}")
    public Map<String, Object> deleteRagDoc(@PathVariable String docId, HttpServletRequest request) {
        requireAdmin(request);
        int deleted = ingestionService.deleteByDocId(docId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("deletedChunks", deleted);
        return result;
    }

    // ─── 知識庫設定 ───

    @GetMapping("/settings")
    public Map<String, Object> getSettings(HttpServletRequest request) {
        requireAdmin(request);
        return settingsService.describe();
    }

    @PatchMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireAdmin(request);
        settingsService.update(body);
        return settingsService.describe();
    }

    @PostMapping("/settings")
    public Map<String, Object> updateSettingsPost(@RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return updateSettings(body, request);
    }

    // ─── ERP AI 設定 ───

    @GetMapping("/agent-settings")
    public Map<String, Object> getAgentSettings(HttpServletRequest request) {
        requireAdmin(request);
        return settingsService.describeAgent();
    }

    @PostMapping("/agent-settings")
    public Map<String, Object> updateAgentSettings(@RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireAdmin(request);
        settingsService.updateAgent(body);
        return settingsService.describeAgent();
    }

    // ─── helpers ───

    private void requireAdmin(HttpServletRequest request) {
        ApiClient client = (ApiClient) request.getAttribute(ApiKeyFilter.ATTR_CLIENT);
        if (client == null || !client.isAdmin()) {
            throw new SecurityException("需要 ADMIN 角色");
        }
    }

    private String generateApiKey() {
        StringBuilder sb = new StringBuilder("gw-");
        for (int i = 0; i < 32; i++) {
            sb.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }
}
