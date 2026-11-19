package com.charphone.aigateway.provider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

/**
 * 多供應商 Model Router：依優先序嘗試供應商，失敗自動 failover 到下一個。
 * 附帶簡易 Circuit Breaker：連續失敗 N 次後暫時跳過該供應商 M 秒。
 */
public class ModelRouter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60_000;

    private final List<LlmProvider> providers;
    private final Map<String, ProviderStats> stats = new ConcurrentHashMap<>();

    public ModelRouter(List<LlmProvider> providers) {
        this.providers = List.copyOf(providers);
        for (LlmProvider p : providers) {
            stats.put(p.name(), new ProviderStats());
        }
        log.info("ModelRouter 初始化，供應商鏈：{}", providers.stream().map(LlmProvider::name).toList());
    }

    @Override
    public String name() {
        return "model-router";
    }

    @Override
    public boolean supportsImages() {
        return providers.stream().anyMatch(LlmProvider::supportsImages);
    }

    @Override
    public ProviderResult chat(ProviderChatRequest request) {
        boolean hasImages = hasImages(request);
        for (int i = 0; i < providers.size(); i++) {
            LlmProvider provider = providers.get(i);
            if (hasImages && !provider.supportsImages()) {
                log.info("ModelRouter: 請求含圖片，跳過不支援圖片的 {}", provider.name());
                continue;
            }
            if (isCircuitOpen(provider.name())) {
                log.warn("ModelRouter: {} Circuit Breaker 開啟，跳過", provider.name());
                continue;
            }
            try {
                long start = System.currentTimeMillis();
                ProviderResult result = provider.chat(withProviderModel(request, provider, i));
                long latency = System.currentTimeMillis() - start;
                recordSuccess(provider.name(), latency);
                log.info("ModelRouter: {} 呼叫成功 ({}ms, prompt={}, completion={})",
                        provider.name(), latency, result.promptTokens(), result.completionTokens());
                return result;
            } catch (Exception e) {
                recordFailure(provider.name(), e);
                log.warn("ModelRouter: {} 呼叫失敗 ({}), 嘗試下一個供應商",
                        provider.name(), e.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("所有供應商皆失敗，無法完成請求");
    }

    @Override
    public Flux<ProviderChunk> stream(ProviderChatRequest request) {
        boolean hasImages = hasImages(request);
        for (int i = 0; i < providers.size(); i++) {
            LlmProvider provider = providers.get(i);
            if (hasImages && !provider.supportsImages()) {
                log.info("ModelRouter: 請求含圖片，跳過不支援圖片的 {}", provider.name());
                continue;
            }
            if (isCircuitOpen(provider.name())) {
                log.warn("ModelRouter: {} Circuit Breaker 開啟，跳過", provider.name());
                continue;
            }
            try {
                log.info("ModelRouter: {} 嘗試串流", provider.name());
                return provider.stream(withProviderModel(request, provider, i))
                        .doOnComplete(() -> recordSuccess(provider.name(), 0))
                        .doOnError(e -> recordFailure(provider.name(), new Exception(e)));
            } catch (Exception e) {
                recordFailure(provider.name(), e);
                log.warn("ModelRouter: {} 串流失敗 ({}), 嘗試下一個供應商",
                        provider.name(), e.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("所有供應商皆失敗，無法完成串流請求");
    }

    private boolean hasImages(ProviderChatRequest request) {
        return request.turns() != null && request.turns().stream()
                .anyMatch(turn -> turn.images() != null && !turn.images().isEmpty());
    }

    /**
     * 主供應商沿用請求指定的模型；failover 供應商改用自身預設模型，
     * 避免把 A 供應商的模型名稱送給 B 供應商。
     */
    private ProviderChatRequest withProviderModel(ProviderChatRequest request, LlmProvider provider, int index) {
        String fallbackModel = provider.defaultModel();
        if (index == 0 || fallbackModel == null || fallbackModel.isBlank()
                || fallbackModel.equals(request.model())) {
            return request;
        }
        return new ProviderChatRequest(fallbackModel, request.systemPrompt(), request.turns(),
                request.tools(), request.temperature());
    }

    /** 回傳所有供應商的即時統計。 */
    public Map<String, Object> getStats() {
        Map<String, Object> result = new ConcurrentHashMap<>();
        for (LlmProvider provider : providers) {
            ProviderStats s = stats.get(provider.name());
            Map<String, Object> info = new ConcurrentHashMap<>();
            info.put("name", provider.name());
            info.put("successCount", s.successCount.get());
            info.put("failureCount", s.failureCount.get());
            info.put("totalLatencyMs", s.totalLatencyMs.get());
            info.put("circuitOpen", isCircuitOpen(provider.name()));
            result.put(provider.name(), info);
        }
        return result;
    }

    private boolean isCircuitOpen(String providerName) {
        ProviderStats s = stats.get(providerName);
        if (s == null) return false;
        int failures = s.failureCount.get();
        long lastFailure = s.lastFailureTime.get();
        return failures >= FAILURE_THRESHOLD
                && (System.currentTimeMillis() - lastFailure) < COOLDOWN_MS;
    }

    private void recordSuccess(String providerName, long latencyMs) {
        ProviderStats s = stats.get(providerName);
        if (s != null) {
            s.successCount.incrementAndGet();
            s.totalLatencyMs.addAndGet(latencyMs);
            s.failureCount.set(0);
        }
    }

    private void recordFailure(String providerName, Exception e) {
        ProviderStats s = stats.get(providerName);
        if (s != null) {
            s.failureCount.incrementAndGet();
            s.lastFailureTime.set(System.currentTimeMillis());
        }
    }

    private static class ProviderStats {
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final AtomicLong lastFailureTime = new AtomicLong(0);
    }
}
