package com.charphone.aigateway.agent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.charphone.aigateway.api.AgentRunRequest;
import com.charphone.aigateway.api.AgentRunResponse;
import com.charphone.aigateway.api.UsageDto;
import com.charphone.aigateway.audit.AppUserContext;
import com.charphone.aigateway.audit.AuditLogDao;
import com.charphone.aigateway.audit.AuditLogDao.AuditRecord;
import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.LlmProvider.FunctionCall;
import com.charphone.aigateway.provider.LlmProvider.FunctionResponse;
import com.charphone.aigateway.provider.LlmProvider.ImagePart;
import com.charphone.aigateway.provider.LlmProvider.ProviderChatRequest;
import com.charphone.aigateway.provider.LlmProvider.ProviderResult;
import com.charphone.aigateway.provider.LlmProvider.Turn;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.service.PromptFormat;
import com.charphone.aigateway.settings.GatewaySettingsService;
import com.charphone.aigateway.settings.GatewaySettingsService.AgentSettings;
import com.charphone.aigateway.tools.ToolRegistry;

/**
 * Agent 核心：tool calling 迴圈。
 * 模型決定 → 執行工具 → 結果回饋 → 再次詢問，直到模型給出最終答案或達最大輪數。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_IMAGES = 3;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");

    private final LlmProvider provider;
    private final ToolRegistry toolRegistry;
    private final AuditLogDao auditLogDao;
    private final AppProperties props;
    private final GatewaySettingsService settingsService;

    public AgentService(LlmProvider provider, ToolRegistry toolRegistry, AuditLogDao auditLogDao,
            AppProperties props, GatewaySettingsService settingsService) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.auditLogDao = auditLogDao;
        this.props = props;
        this.settingsService = settingsService;
    }

    public AgentRunResponse run(AgentRunRequest request, ApiClient client) {
        return run(request, client, AppUserContext.EMPTY);
    }

    public AgentRunResponse run(AgentRunRequest request, ApiClient client, AppUserContext appUser) {
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        AgentSettings settings = settingsService.getAgentSettings();
        String model = props.getActiveModel();
        int requestedRounds = request.maxToolRounds() != null && request.maxToolRounds() > 0
                ? request.maxToolRounds() : settings.maxRounds();
        int maxRounds = Math.min(Math.max(requestedRounds, 1), MAX_ROUNDS);

        List<ImagePart> imageParts = toImageParts(request.images());
        List<Turn> turns = new ArrayList<>();
        turns.add(Turn.user(request.userMessage(), imageParts.isEmpty() ? null : imageParts));
        List<AgentRunResponse.ToolCallInfo> toolCalls = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;

        String finalText = null;
        for (int round = 1; round <= maxRounds; round++) {
            ProviderChatRequest providerRequest = new ProviderChatRequest(
                    model, systemPrompt(), List.copyOf(turns), toolRegistry.declarations(),
                    settings.temperature());
            ProviderResult result = provider.chat(providerRequest);
            promptTokens += result.promptTokens();
            completionTokens += result.completionTokens();

            if (result.functionCalls().isEmpty()) {
                finalText = result.text() != null ? result.text() : "（模型未回覆內容）";
                break;
            }
            turns.add(Turn.modelCalls(result.functionCalls()));

            List<FunctionResponse> responses = new ArrayList<>();
            for (FunctionCall call : result.functionCalls()) {
                ToolRegistry.ToolExecutionResult tr = toolRegistry.execute(
                        call.name(), call.args(), client);
                log.info("Agent 工具呼叫 round={} tool={} ok={} latency={}ms",
                        round, call.name(), tr.ok(), tr.latencyMs());
                auditLogDao.recordToolCall(new AuditRecord(
                        requestId, client.id(), client.clientName(), appUser.userId(), appUser.username(),
                        appUser.sessionId(), "/api/v1/agent/run", model, false,
                        truncateJson(call.args()), truncatePayload(tr.payload()),
                        result.promptTokens(), result.completionTokens(), tr.latencyMs(),
                        tr.ok() ? 200 : 500));
                toolCalls.add(new AgentRunResponse.ToolCallInfo(call.name(), tr.ok(), tr.latencyMs()));
                responses.add(new FunctionResponse(call.name(), tr.payload()));
            }
            turns.add(Turn.toolResponses(responses));
        }

        if (finalText == null) {
            finalText = "已達最大工具呼叫輪數（" + maxRounds + "），無法完成查詢。請嘗試縮小問題範圍。";
        }

        long latency = System.currentTimeMillis() - start;
        String auditPrompt = imageParts.isEmpty()
                ? request.userMessage()
                : request.userMessage() + " [附圖 " + imageParts.size() + " 張]";
        auditLogDao.record(new AuditRecord(
                requestId, client.id(), client.clientName(), appUser.userId(), appUser.username(),
                appUser.sessionId(), "/api/v1/agent/run", model, false,
                auditPrompt, finalText, promptTokens, completionTokens, latency, 200));

        UsageDto usage = new UsageDto(provider.name(), model, promptTokens, completionTokens,
                promptTokens + completionTokens);
        return new AgentRunResponse(requestId, request.sessionId(), finalText, toolCalls, usage);
    }

    /**
     * 驗證並轉換前端送來的圖片。base64 只在記憶體中處理，不寫入稽核紀錄。
     *
     * @param images 前端圖片清單，可為 null
     * @return 驗證後的圖片片段；無圖片時回傳空清單
     */
    private List<ImagePart> toImageParts(List<AgentRunRequest.ImageInput> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("一次最多附上 " + MAX_IMAGES + " 張圖片");
        }

        List<ImagePart> parts = new ArrayList<>();
        int totalBytes = 0;
        for (AgentRunRequest.ImageInput image : images) {
            if (image == null) {
                throw new IllegalArgumentException("圖片內容不可為空");
            }
            String mimeType = normalizeMimeType(image.mimeType());
            if (!ALLOWED_IMAGE_TYPES.contains(mimeType)) {
                throw new IllegalArgumentException("不支援的圖片格式，僅支援 PNG、JPEG、WEBP");
            }
            String data = normalizeImageData(image.data());
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("圖片內容不是有效的 base64", e);
            }
            if (decoded.length == 0) {
                throw new IllegalArgumentException("圖片內容不可為空");
            }
            if (decoded.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("單張圖片不可超過 5MB");
            }
            totalBytes += decoded.length;
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw new IllegalArgumentException("圖片總大小不可超過 12MB");
            }
            parts.add(new ImagePart(mimeType, data));
        }
        return List.copyOf(parts);
    }

    private static String normalizeMimeType(String mimeType) {
        String value = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(value) ? "image/jpeg" : value;
    }

    private static String normalizeImageData(String data) {
        String value = data == null ? "" : data.trim();
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma > 0) {
                value = value.substring(comma + 1);
            }
        }
        return value.replaceAll("\\s+", "");
    }

    private String systemPrompt() {
        return """
                你是公司的企業 AI 助理，透過提供的工具查詢 ERP 實際資料（訂單、出貨、發票、庫存、生產、帳務、客戶、KPI）。

                規則：
                - 一律使用繁體中文回答。
                - 金額皆為新台幣（TWD）。
                - 狀態請用具體語意呈現（如「生產中」「部分出貨」「逾期」），不要回覆代碼或裸數字。
                - 需要資料時必須呼叫工具查詢，嚴禁憑空編造數字。
                - 若使用者附上圖片，先根據圖片內容回答；只有需要 ERP 即時資料時才呼叫工具，不要因為有附圖就忽略工具。
                - 工具回傳空資料時，如實告知「查無資料」，不要猜測。
                - 遇到「明年」「今年」「下個月」等相對時間，先依今天日期換算成西元年份；若工具或知識庫內容只有其他年度，明確說明沒有該年度資料，不要把它當成該年度答案。
                - 若問題要求「總數、合計、平均、幾天」等彙總，必須確認工具回傳資料完整；只有部分片段、或 query_knowledge 的 totalChunks 不足以涵蓋完整文件時，明確回答「目前檢索到的資料不足，無法計算」，不得用常識、慣例、推估或部分片段加總補足。
                - query_knowledge 只回傳 top K 片段，不代表完整文件；不得把片段中的部分日期自行加總成全年數字。
                - 回答彙整重點即可，不要逐筆複製全部原始資料；超過5筆時列出重點並說明總數。
                - 今天日期：%s。

                %s
                """.formatted(LocalDate.now(), PromptFormat.MARKDOWN_RULES);
    }

    private String truncateJson(Object value) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            return json.length() <= 20000 ? json : json.substring(0, 20000);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncatePayload(Map<String, Object> payload) {
        Object data = payload.get("data");
        String s = data == null ? String.valueOf(payload.get("message")) : String.valueOf(data);
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...(截斷)";
    }
}
