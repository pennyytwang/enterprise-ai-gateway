package com.charphone.aigateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gateway 設定：gemini 連線與稽核開關。
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class AppProperties {

    private final Gemini gemini = new Gemini();
    private final OpenAiCompat openaiCompat = new OpenAiCompat();
    private final Audit audit = new Audit();
    private final RateLimit rateLimit = new RateLimit();
    private final Rag rag = new Rag();
    private final Agent agent = new Agent();
    /** RAG 原始檔保存目錄（上傳後可下載原檔） */
    private String ragStorageDir = "./rag-docs";
    /** 啟用中的供應商：gemini（設定值）| openai-compat（xAI/Groq/OpenRouter 等 OpenAI 相容端點） */
    private String provider = "gemini";
    /** 備援供應商鏈（逗號分隔），如 "openai-compat,gemini"。主供應商失敗時依序嘗試 */
    private String fallbackProviders = "";

    public Gemini getGemini() {
        return gemini;
    }

    public OpenAiCompat getOpenaiCompat() {
        return openaiCompat;
    }

    public Audit getAudit() {
        return audit;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Rag getRag() {
        return rag;
    }

    public Agent getAgent() {
        return agent;
    }

    public String getRagStorageDir() {
        return ragStorageDir;
    }

    public void setRagStorageDir(String ragStorageDir) {
        this.ragStorageDir = ragStorageDir;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getFallbackProviders() {
        return fallbackProviders;
    }

    public void setFallbackProviders(String fallbackProviders) {
        this.fallbackProviders = fallbackProviders;
    }

    /** 目前啟用供應商的預設模型（服務層 model 留空時用）。 */
    public String getActiveModel() {
        return "openai-compat".equalsIgnoreCase(provider) ? openaiCompat.getModel() : gemini.getModel();
    }

    public static class Gemini {
        private String apiKey = "";
        private String apiKeyFile = "";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private String model = "gemini-3.6-flash";
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 120;

        public String getApiKey() {
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey.trim();
            }
            if (apiKeyFile != null && !apiKeyFile.isBlank()) {
                try {
                    String loaded = Files.readString(Path.of(apiKeyFile)).trim();
                    if (!loaded.isEmpty()) {
                        return loaded;
                    }
                } catch (IOException e) {
                    // 留給啟動檢查回報
                }
            }
            return "";
        }

        public String getApiKeyFile() {
            return apiKeyFile;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public void setApiKeyFile(String apiKeyFile) {
            this.apiKeyFile = apiKeyFile;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }
    }

    /**
     * OpenAI 相容端點設定（xAI / Groq / OpenRouter 等）。
     * 供應商名稱由 baseUrl 推導，寫入稽核與用量。
     */
    public static class OpenAiCompat {
        private String apiKey = "";
        private String apiKeyFile = "";
        private String baseUrl = "https://api.x.ai/v1";
        private String model = "grok-4-fast";
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 120;

        public String getApiKey() {
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey.trim();
            }
            if (apiKeyFile != null && !apiKeyFile.isBlank()) {
                try {
                    String loaded = Files.readString(Path.of(apiKeyFile)).trim();
                    if (!loaded.isEmpty()) {
                        return loaded;
                    }
                } catch (IOException e) {
                    // 留給啟動檢查回報
                }
            }
            return "";
        }

        public String getApiKeyFile() {
            return apiKeyFile;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public void setApiKeyFile(String apiKeyFile) {
            this.apiKeyFile = apiKeyFile;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }
    }

    public static class Audit {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Agent {
        private double temperature = 0.2;
        private int maxRounds = 8;
        private int knowledgeTopK = 5;
        private double knowledgeMinScore = 0.0;

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        public int getKnowledgeTopK() {
            return knowledgeTopK;
        }

        public void setKnowledgeTopK(int knowledgeTopK) {
            this.knowledgeTopK = knowledgeTopK;
        }

        public double getKnowledgeMinScore() {
            return knowledgeMinScore;
        }

        public void setKnowledgeMinScore(double knowledgeMinScore) {
            this.knowledgeMinScore = knowledgeMinScore;
        }
    }

    public static class Rag {
        private double temperature = 0.1;
        private int topK = 3;
        private double minScore = 0.0;

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }
    }

    public static class RateLimit {
        private boolean enabled = false;
        private int defaultDaily = 500;
        private int defaultPerMin = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultDaily() {
            return defaultDaily;
        }

        public void setDefaultDaily(int defaultDaily) {
            this.defaultDaily = defaultDaily;
        }

        public int getDefaultPerMin() {
            return defaultPerMin;
        }

        public void setDefaultPerMin(int defaultPerMin) {
            this.defaultPerMin = defaultPerMin;
        }
    }
}
