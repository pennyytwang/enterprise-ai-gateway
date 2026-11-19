package com.charphone.aigateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.charphone.aigateway.provider.GeminiProvider;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.ModelRouter;
import com.charphone.aigateway.provider.OpenAiCompatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 供應商註冊：建立所有可用供應商，包裝為 ModelRouter（支援 failover）。
 * gateway.provider=主供應商；gateway.fallback-providers=備援鏈（逗號分隔）。
 */
@Configuration
public class ProviderConfig {

    @Bean
    public LlmProvider modelRouter(AppProperties props,
            WebClient geminiWebClient, WebClient openAiCompatWebClient,
            ObjectMapper objectMapper) {
        List<LlmProvider> chain = new ArrayList<>();
        String primary = props.getProvider();

        // 加入主供應商
        chain.add(createProvider(primary, props, geminiWebClient, openAiCompatWebClient, objectMapper));

        // 加入備援供應商
        String fallbacks = props.getFallbackProviders();
        if (fallbacks != null && !fallbacks.isBlank()) {
            for (String name : fallbacks.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(primary)) {
                    chain.add(createProvider(trimmed, props, geminiWebClient, openAiCompatWebClient, objectMapper));
                }
            }
        }

        if (chain.size() == 1) {
            return chain.get(0);
        }
        return new ModelRouter(chain);
    }

    private LlmProvider createProvider(String name, AppProperties props,
            WebClient geminiWebClient, WebClient openAiCompatWebClient,
            ObjectMapper objectMapper) {
        return switch (name.toLowerCase()) {
            case "gemini" -> new GeminiProvider(props, geminiWebClient, objectMapper);
            case "openai-compat" -> new OpenAiCompatProvider(props, openAiCompatWebClient, objectMapper);
            default -> throw new IllegalArgumentException("不支援的供應商: " + name);
        };
    }
}
