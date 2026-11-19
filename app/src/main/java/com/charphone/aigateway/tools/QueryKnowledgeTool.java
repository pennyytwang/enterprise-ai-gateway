package com.charphone.aigateway.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.charphone.aigateway.rag.RagService;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.settings.GatewaySettingsService;
import com.charphone.aigateway.settings.GatewaySettingsService.AgentSettings;

/**
 * query_knowledge：從企業知識庫（PDF/Word/TXT 文件）檢索相關內容。
 * 所有已認證 client 可用（知識庫非敏感）。
 */
@Component
public class QueryKnowledgeTool implements ToolHandler {

    private final RagService ragService;
    private final GatewaySettingsService settingsService;

    public QueryKnowledgeTool(RagService ragService, GatewaySettingsService settingsService) {
        this.ragService = ragService;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "query_knowledge";
    }

    @Override
    public String description() {
        return "從企業知識庫（PDF/Word/TXT 文件）檢索相關內容。適用於查閱公司規範、流程、產品規格、合約條款等非結構化文件。";
    }

    @Override
    public String requiredRole() {
        return "USER";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolHandler.schema(
                List.of("query"),
                Map.of(
                        "query", ToolHandler.prop("STRING", "查詢問題（自然語言），例如：「公司的報價流程是什麼？」"),
                        "topK", ToolHandler.prop("INTEGER", "選擇性：回傳最相關的片段數量；不可超過管理員設定值")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, ApiClient client) {
        String query = ToolHandler.strArg(args, "query");
        AgentSettings settings = settingsService.getAgentSettings();
        int configuredTopK = Math.min(Math.max(settings.knowledgeTopK(), 1), 10);
        int topK = Math.min(Math.max(ToolHandler.intArg(args, "topK", configuredTopK), 1),
                configuredTopK);

        Map<String, Object> result = ragService.retrieve(query, topK);
        List<?> rawHits = (List<?>) result.getOrDefault("results", List.of());
        List<?> hits = RagService.filterByMinScore(rawHits, settings.knowledgeMinScore());

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("results", hits);
        response.put("totalChunks", hits.size());
        response.put("coverage", "top_k_only");
        response.put("note", "只回傳最相關片段，可能未涵蓋完整文件；總數、合計或全年統計問題若資料不足，應明確說明無法計算。");
        return response;
    }
}
