package com.charphone.aigateway.tools;

import java.util.List;
import java.util.Map;

import com.charphone.aigateway.security.ApiClient;

/**
 * 工具 Handler：每支內部工具實作此介面，由 ToolRegistry 統一註冊與執行。
 * parametersSchema 使用 Gemini/OpenAI JSON Schema 相容格式（型別大寫）。
 */
public interface ToolHandler {

    String name();

    String description();

    Map<String, Object> parametersSchema();

    /** 呼叫此工具所需角色，預設 ADMIN（帳務/營收/成本敏感）。 */
    default String requiredRole() {
        return "ADMIN";
    }

    Object execute(Map<String, Object> args, ApiClient client) throws Exception;

    /* ---- schema 建構輔助 ---- */

    static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static Map<String, Object> enumProp(List<String> values, String description) {
        return Map.of("type", "STRING", "description", description, "enum", values);
    }

    static Map<String, Object> schema(List<String> required, Map<String, Object> properties) {
        return Map.of("type", "OBJECT", "properties", properties, "required", required);
    }

    /* ---- 引數取值輔助 ---- */

    static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必要參數: " + key);
        }
        return String.valueOf(v).trim();
    }

    static String strArgOrNull(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? defaultValue : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static Integer intArgOrNull(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 統一成功回覆格式。 */
    static Map<String, Object> ok(Object data) {
        return Map.of("ok", true, "data", data);
    }
}
