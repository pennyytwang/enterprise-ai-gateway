package com.charphone.aigateway.rag;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.charphone.aigateway.audit.AppUserContext;
import com.charphone.aigateway.audit.AuditLogDao;
import com.charphone.aigateway.audit.AuditLogDao.AuditRecord;
import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.LlmProvider.ProviderChatRequest;
import com.charphone.aigateway.provider.LlmProvider.ProviderResult;
import com.charphone.aigateway.provider.LlmProvider.Turn;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.service.PromptFormat;
import com.charphone.aigateway.settings.GatewaySettingsService;
import com.charphone.aigateway.settings.GatewaySettingsService.RagSettings;

/**
 * RAG 問答：先用向量檢索撈出相關片段，再交給 LLM 依照「僅以知識庫內容」回答。
 * 提供「問問題直接得答案」的模式（相對於 RagService.retrieve 只回原文片段）。
 */
@Service
public class RagAskService {

    private static final Logger log = LoggerFactory.getLogger(RagAskService.class);
    private static final int MAX_TOP_K = 10;

    private final RagService ragService;
    private final LlmProvider provider;
    private final AuditLogDao auditLogDao;
    private final AppProperties props;
    private final GatewaySettingsService settingsService;

    public RagAskService(RagService ragService, LlmProvider provider, AuditLogDao auditLogDao,
            AppProperties props, GatewaySettingsService settingsService) {
        this.ragService = ragService;
        this.provider = provider;
        this.auditLogDao = auditLogDao;
        this.props = props;
        this.settingsService = settingsService;
    }

    /** 問答：擷取 → LLM 摘要 → 回傳答案與來源。 */
    public Map<String, Object> ask(String query, int topK, ApiClient client) {
        return ask(query, topK, client, AppUserContext.EMPTY);
    }

    public Map<String, Object> ask(String query, int topK, ApiClient client, AppUserContext appUser) {
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        RagSettings settings = settingsService.getRagSettings();
        int k = topK > 0 ? Math.min(topK, MAX_TOP_K) : settings.topK();
        String model = props.getActiveModel();

        Map<String, Object> retrieval = ragService.retrieve(query, k);
        List<?> rawHits = (List<?>) retrieval.getOrDefault("results", List.of());
        List<?> hits = RagService.filterByMinScore(rawHits, settings.minScore());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("query", query);
        result.put("totalChunks", hits.size());
        result.put("sources", hits);

        if (hits.isEmpty()) {
            result.put("answer", "知識庫中找不到與此問題相關的內容。");
            result.put("answerable", false);
            writeAudit(requestId, client, appUser, model, query, (String) result.get("answer"), 0, 0, start, 200);
            return result;
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Map<?, ?> h = (Map<?, ?>) hits.get(i);
            context.append("[片段 ").append(i + 1).append("]")
                    .append("（來源 ").append(h.get("filename")).append("，score ")
                    .append(h.get("score")).append("）\n")
                    .append(h.get("text")).append("\n\n");
        }

        String systemPrompt =
                "你是企業知識庫的問答助理。請嚴格只根據下列「知識庫片段」回答使用者的問題。\n"
                + "規則：\n"
                + "1. 若片段中有明確答案，請直接、精確地回答（例如日期、數字、收盤價等請如實引用）。\n"
                + "2. 若問題包含「明年」「今年」等相對年份，請先依今天日期（" + LocalDate.now()
                + "）換算；片段若只有其他年度，明確說明沒有該年度資料，不要以其他年度內容代替。\n"
                + "3. 若片段不足或無相關內容，請明確說「知識庫中沒有這項資訊」，不要自行臆測。\n"
                + "4. 使用繁體中文回答；可簡短總結，但關鍵數字必須準確。\n"
                + PromptFormat.MARKDOWN_RULES
                + "\n===== 知識庫片段開始 =====\n"
                + context
                + "===== 知識庫片段結束 =====";

        try {
            ProviderResult r = provider.chat(new ProviderChatRequest(
                    model, systemPrompt, List.of(Turn.user(query)), null, settings.temperature()));
            String answer = (r.text() == null || r.text().isBlank())
                    ? "（模型未回傳內容）" : r.text().trim();
            result.put("answer", answer);
            result.put("answerable", true);
            writeAudit(requestId, client, appUser, model, query, answer, r.promptTokens(), r.completionTokens(),
                    start, 200);
        } catch (Exception e) {
            log.error("RAG 問答 LLM 呼叫失敗: {}", e.getMessage());
            result.put("answer", "無法總結知識庫內容：" + e.getMessage());
            result.put("answerable", false);
            writeAudit(requestId, client, appUser, model, query, (String) result.get("answer"), 0, 0, start, 500);
        }
        return result;
    }

    private void writeAudit(String requestId, ApiClient client, AppUserContext appUser, String model,
            String prompt, String response, int promptTokens, int completionTokens, long start, int statusCode) {
        auditLogDao.record(new AuditRecord(requestId, client.id(), client.clientName(), appUser.userId(),
                appUser.username(), appUser.sessionId(), "/api/v1/rag/ask", model, false, prompt, response,
                promptTokens, completionTokens, System.currentTimeMillis() - start, statusCode));
    }
}
