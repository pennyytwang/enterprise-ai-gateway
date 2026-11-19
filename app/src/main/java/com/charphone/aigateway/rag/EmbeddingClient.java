package com.charphone.aigateway.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.charphone.aigateway.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Gemini Embedding Client：把文字轉成向量（3072 維）。
 * 單段 embedContent API + 429 自動 retry（指數退避）+ 請求間隔防限流。
 */
@org.springframework.stereotype.Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int MAX_TEXT_LENGTH = 8000;
    private static final int MAX_RETRIES = 3;
    private static final long DELAY_BETWEEN_MS = 500;

    private final AppProperties props;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(AppProperties props, WebClient geminiWebClient, ObjectMapper objectMapper) {
        this.props = props;
        this.webClient = geminiWebClient;
        this.objectMapper = objectMapper;
    }

    /** 單段文字 Embedding。 */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding 文字不可為空");
        }
        String key = getApiKey();
        String truncated = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;

        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("content").putArray("parts").addObject().put("text", truncated);

        JsonNode resp = callApiWithRetry(key, body);

        if (resp == null || !resp.hasNonNull("embedding")) {
            throw new IllegalStateException("Embedding API 回傳空結果");
        }

        JsonNode values = resp.path("embedding").path("values");
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }

    /** 批次 Embedding：逐段呼叫 + 自動 retry + 請求間隔。 */
    public List<float[]> embedBatch(List<String> texts) {
        String key = getApiKey();
        List<float[]> results = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            log.info("Embedding {}/{}（{} 字）", i + 1, texts.size(), texts.get(i).length());

            String truncated = texts.get(i).length() > MAX_TEXT_LENGTH
                    ? texts.get(i).substring(0, MAX_TEXT_LENGTH) : texts.get(i);

            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("content").putArray("parts").addObject().put("text", truncated);

            JsonNode resp = callApiWithRetry(key, body);

            if (resp == null || !resp.hasNonNull("embedding")) {
                throw new IllegalStateException("Embedding API 回傳空結果（第 " + (i + 1) + " 段）");
            }

            JsonNode values = resp.path("embedding").path("values");
            float[] vector = new float[values.size()];
            for (int j = 0; j < values.size(); j++) {
                vector[j] = (float) values.get(j).asDouble();
            }
            results.add(vector);

            // 請求間隔，避免觸發限流
            if (i < texts.size() - 1) {
                try { Thread.sleep(DELAY_BETWEEN_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        return results;
    }

    private String getApiKey() {
        String key = props.getGemini().getApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未設定 Gemini API Key（Embedding 需要）");
        }
        return key;
    }

    /** 呼叫 Gemini embedContent API，自動 retry 429（指數退避）。 */
    private JsonNode callApiWithRetry(String key, ObjectNode body) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return webClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/models/gemini-embedding-2:embedContent")
                                .queryParam("key", key)
                                .build())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(30))
                        .block();
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_RETRIES) {
                    long waitSec = (long) Math.pow(2, attempt) * 5;
                    log.warn("Gemini Embedding 429 限流，第 {} 次重試，等待 {}s", attempt, waitSec);
                    try { Thread.sleep(waitSec * 1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                log.error("Gemini Embedding 錯誤 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new IllegalStateException("Embedding API 錯誤: " + e.getResponseBodyAsString(), e);
            }
        }
        throw new IllegalStateException("Embedding API 重試次數用盡");
    }
}
