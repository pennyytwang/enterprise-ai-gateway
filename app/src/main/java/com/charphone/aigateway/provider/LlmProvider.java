package com.charphone.aigateway.provider;

import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;

/**
 * 雲端 LLM 供應商抽象層（Phase 2 擴充 OpenAI/Azure/Claude）。
 * 對話以 Turn 為單位：純文字、functionCall（模型要求呼叫工具）、functionResponse（工具結果回饋）。
 */
public interface LlmProvider {

    /** 供應商名稱（寫入稽核/用量） */
    String name();

    /** 非串流呼叫，回傳完整回覆、用量與工具呼叫請求。 */
    ProviderResult chat(ProviderChatRequest request);

    /** 串流呼叫，Flux 依序送出文字增量；完成時 onComplete。 */
    Flux<ProviderChunk> stream(ProviderChatRequest request);

    /** 供應商預設模型；供 failover 時切換到正確模型，未知時回傳 null。 */
    default String defaultModel() {
        return null;
    }

    /** 是否支援圖片輸入。 */
    default boolean supportsImages() {
        return false;
    }

    /** 圖片附件：mimeType 為 image/png、image/jpeg、image/webp；data 為 base64 內容。 */
    record ImagePart(String mimeType, String data) {
    }

    /** 對話一輪：role 為 "user"/"model"；文字、圖片與函式呼叫/結果可並存。 */
    record Turn(String role, String text, List<FunctionCall> functionCalls,
            List<FunctionResponse> functionResponses, List<ImagePart> images) {

        public Turn(String role, String text, List<FunctionCall> functionCalls,
                List<FunctionResponse> functionResponses) {
            this(role, text, functionCalls, functionResponses, null);
        }

        public static Turn user(String text) {
            return new Turn("user", text, null, null, null);
        }

        public static Turn user(String text, List<ImagePart> images) {
            return new Turn("user", text, null, null, images);
        }

        public static Turn model(String text) {
            return new Turn("model", text, null, null, null);
        }

        public static Turn modelCalls(List<FunctionCall> calls) {
            return new Turn("model", null, calls, null, null);
        }

        public static Turn toolResponses(List<FunctionResponse> responses) {
            return new Turn("user", null, null, responses, null);
        }
    }

    /** 工具宣告（OpenAI/Gemini JSON Schema 相容）。 */
    record FunctionDeclaration(String name, String description, Map<String, Object> parameters) {
    }

    /** 模型要求的工具呼叫。thoughtSignature 為 Gemini 3 必要回傳欄位（可為 null）。 */
    record FunctionCall(String name, Map<String, Object> args, String thoughtSignature) {

        public FunctionCall(String name, Map<String, Object> args) {
            this(name, args, null);
        }
    }

    /** 回饋給模型的工具執行結果。 */
    record FunctionResponse(String name, Object response) {
    }

    /** 送給供應商的完整請求。 */
    record ProviderChatRequest(String model, String systemPrompt, List<Turn> turns,
            List<FunctionDeclaration> tools, Double temperature) {

        public ProviderChatRequest(String model, String systemPrompt, List<Turn> turns,
                List<FunctionDeclaration> tools) {
            this(model, systemPrompt, turns, tools, null);
        }
    }

    /** 完整結果：text + token 用量 + 工具呼叫請求（可能為空）。 */
    record ProviderResult(String text, int promptTokens, int completionTokens, List<FunctionCall> functionCalls) {
    }

    /** 串流增量：一段文字；usage 欄位有值時為累計 token 數（Gemini 每 chunk 帶累計值）。 */
    record ProviderChunk(String text, int promptTokens, int completionTokens) {
    }
}
