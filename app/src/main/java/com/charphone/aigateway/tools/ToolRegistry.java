package com.charphone.aigateway.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charphone.aigateway.provider.LlmProvider.FunctionDeclaration;
import com.charphone.aigateway.security.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具註冊表：收集所有 ToolHandler，提供宣告清單給 LLM、統一執行
 * （權限檢查、逾時、結果大小上限）。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int MAX_RESULT_CHARS = 32000;
    private static final long TIMEOUT_SECONDS = 20;

    private final Map<String, ToolHandler> handlers;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-exec");
        t.setDaemon(true);
        return t;
    });

    public ToolRegistry(List<ToolHandler> handlerList, ObjectMapper objectMapper) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ToolHandler::name, Function.identity()));
        this.objectMapper = objectMapper;
        log.info("Tool Registry 註冊 {} 支工具: {}", handlers.size(), handlers.keySet());
    }

    public List<FunctionDeclaration> declarations() {
        return handlers.values().stream()
                .map(h -> new FunctionDeclaration(h.name(), h.description(), h.parametersSchema()))
                .toList();
    }

    /** 執行工具；一律回傳 payload（ok/error 包裝），不拋例外。 */
    public ToolExecutionResult execute(String name, Map<String, Object> args, ApiClient client) {
        long start = System.currentTimeMillis();
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            return error("UNKNOWN_TOOL", "工具不存在: " + name, start);
        }
        if ("ADMIN".equals(handler.requiredRole()) && !client.isAdmin()) {
            return error("FORBIDDEN", "此工具僅限系統管理者使用", start);
        }
        Future<Object> future = executor.submit(
                () -> handler.execute(args == null ? Map.of() : args, client));
        try {
            Object data = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return success(data, start);
        } catch (TimeoutException e) {
            future.cancel(true);
            return error("TIMEOUT", "工具執行逾時（" + TIMEOUT_SECONDS + " 秒）", start);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.warn("工具 {} 執行失敗: {}", name, root.toString());
            return error("TOOL_ERROR", String.valueOf(root.getMessage()), start);
        }
    }

    private ToolExecutionResult success(Object data, long start) {
        Map<String, Object> payload = Map.of("ok", true, "data", data == null ? List.of() : data);
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() > MAX_RESULT_CHARS) {
                payload = Map.of(
                        "ok", true,
                        "truncated", true,
                        "note", "結果超過大小上限已截斷，請縮小查詢範圍（縮短日期區間或加過濾條件）",
                        "preview", json.substring(0, 8000));
            }
        } catch (Exception e) {
            log.warn("工具結果序列化失敗: {}", e.getMessage());
        }
        return new ToolExecutionResult(payload, System.currentTimeMillis() - start);
    }

    private ToolExecutionResult error(String code, String message, long start) {
        return new ToolExecutionResult(Map.of("ok", false, "error", code, "message", message),
                System.currentTimeMillis() - start);
    }

    public record ToolExecutionResult(Map<String, Object> payload, long latencyMs) {

        public boolean ok() {
            return Boolean.TRUE.equals(payload.get("ok"));
        }
    }
}
