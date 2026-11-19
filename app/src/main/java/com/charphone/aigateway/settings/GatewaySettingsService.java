package com.charphone.aigateway.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.charphone.aigateway.config.AppProperties;

/**
 * 讀取與更新 AI Gateway 的執行期設定。
 *
 * <p>設定優先序為資料庫、環境變數、application.yml 預設值。</p>
 */
@Service
public class GatewaySettingsService {

    public static final String KEY_RAG_TEMPERATURE = "rag.temperature";
    public static final String KEY_RAG_TOP_K = "rag.top_k";
    public static final String KEY_RAG_MIN_SCORE = "rag.min_score";

    public static final String KEY_AGENT_TEMPERATURE = "agent.temperature";
    public static final String KEY_AGENT_MAX_ROUNDS = "agent.max_rounds";
    public static final String KEY_AGENT_KNOWLEDGE_TOP_K = "agent.knowledge_top_k";
    public static final String KEY_AGENT_KNOWLEDGE_MIN_SCORE = "agent.knowledge_min_score";

    private static final String ENV_RAG_TEMPERATURE = "GATEWAY_RAG_TEMPERATURE";
    private static final String ENV_RAG_TOP_K = "GATEWAY_RAG_TOP_K";
    private static final String ENV_RAG_MIN_SCORE = "GATEWAY_RAG_MIN_SCORE";

    private static final String ENV_AGENT_TEMPERATURE = "GATEWAY_AGENT_TEMPERATURE";
    private static final String ENV_AGENT_MAX_ROUNDS = "GATEWAY_AGENT_MAX_ROUNDS";
    private static final String ENV_AGENT_KNOWLEDGE_TOP_K = "GATEWAY_AGENT_RAG_TOP_K";
    private static final String ENV_AGENT_KNOWLEDGE_MIN_SCORE = "GATEWAY_AGENT_RAG_MIN_SCORE";

    private static final double TEMPERATURE_MIN = 0.0;
    private static final double TEMPERATURE_MAX = 2.0;
    private static final int TOP_K_MIN = 1;
    private static final int TOP_K_MAX = 10;
    private static final int MAX_ROUNDS_MIN = 1;
    private static final int MAX_ROUNDS_MAX = 10;
    private static final double MIN_SCORE_MIN = 0.0;
    private static final double MIN_SCORE_MAX = 1.0;

    private static final Logger log = LoggerFactory.getLogger(GatewaySettingsService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties props;

    public GatewaySettingsService(@Qualifier("gatewayJdbcTemplate") JdbcTemplate jdbcTemplate,
            AppProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    /**
     * 取得目前有效的 RAG 設定。
     *
     * @return 已套用 DB 覆寫與預設值的設定
     */
    public RagSettings getRagSettings() {
        double temperature = validTemperature(readDouble(KEY_RAG_TEMPERATURE,
                props.getRag().getTemperature()));
        int topK = validTopK(readInt(KEY_RAG_TOP_K, props.getRag().getTopK()));
        double minScore = validMinScore(readDouble(KEY_RAG_MIN_SCORE,
                props.getRag().getMinScore()));
        return new RagSettings(temperature, topK, minScore);
    }

    /**
     * 取得 ERP AI 的執行期設定。
     *
     * @return 已套用 DB 覆寫與預設值的 Agent 設定
     */
    public AgentSettings getAgentSettings() {
        double temperature = validTemperature(readDouble(KEY_AGENT_TEMPERATURE,
                props.getAgent().getTemperature()));
        int maxRounds = validMaxRounds(readInt(KEY_AGENT_MAX_ROUNDS,
                props.getAgent().getMaxRounds()));
        int knowledgeTopK = validAgentKnowledgeTopK(readInt(KEY_AGENT_KNOWLEDGE_TOP_K,
                props.getAgent().getKnowledgeTopK()));
        double knowledgeMinScore = validMinScore(readDouble(KEY_AGENT_KNOWLEDGE_MIN_SCORE,
                props.getAgent().getKnowledgeMinScore()));
        return new AgentSettings(temperature, maxRounds, knowledgeTopK, knowledgeMinScore);
    }

    /**
     * 取得可供管理頁顯示的設定與來源資訊。
     *
     * @return 包含 effective value、來源與允許範圍的結構
     */
    public Map<String, Object> describe() {
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("temperature", describeValue(KEY_RAG_TEMPERATURE,
                props.getRag().getTemperature(), ENV_RAG_TEMPERATURE,
                TEMPERATURE_MIN, TEMPERATURE_MAX));
        rag.put("topK", describeValue(KEY_RAG_TOP_K,
                props.getRag().getTopK(), ENV_RAG_TOP_K,
                TOP_K_MIN, TOP_K_MAX));
        rag.put("minScore", describeValue(KEY_RAG_MIN_SCORE,
                props.getRag().getMinScore(), ENV_RAG_MIN_SCORE,
                MIN_SCORE_MIN, MIN_SCORE_MAX));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rag", rag);
        return result;
    }

    /**
     * 取得可供 ERP AI 設定頁顯示的值與來源資訊。
     *
     * @return 包含 effective value、來源與允許範圍的 Agent 設定
     */
    public Map<String, Object> describeAgent() {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("temperature", describeValue(KEY_AGENT_TEMPERATURE,
                props.getAgent().getTemperature(), ENV_AGENT_TEMPERATURE,
                TEMPERATURE_MIN, TEMPERATURE_MAX));
        agent.put("maxRounds", describeValue(KEY_AGENT_MAX_ROUNDS,
                props.getAgent().getMaxRounds(), ENV_AGENT_MAX_ROUNDS,
                MAX_ROUNDS_MIN, MAX_ROUNDS_MAX));
        agent.put("knowledgeTopK", describeValue(KEY_AGENT_KNOWLEDGE_TOP_K,
                props.getAgent().getKnowledgeTopK(), ENV_AGENT_KNOWLEDGE_TOP_K,
                TOP_K_MIN, TOP_K_MAX));
        agent.put("knowledgeMinScore", describeValue(KEY_AGENT_KNOWLEDGE_MIN_SCORE,
                props.getAgent().getKnowledgeMinScore(), ENV_AGENT_KNOWLEDGE_MIN_SCORE,
                MIN_SCORE_MIN, MIN_SCORE_MAX));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        return result;
    }

    /**
     * 更新 RAG 設定；只更新請求中有提供的欄位。
     *
     * @param body 包含 temperature、topK、minScore 的請求內容
     * @return 更新後的有效設定
     * @throws IllegalArgumentException 欄位格式或範圍不合法
     */
    public RagSettings update(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("至少需提供一個設定欄位");
        }

        Double temperature = asDouble(body.get("temperature"), "temperature");
        Integer topK = asInteger(body.get("topK"), "topK");
        Double minScore = asDouble(body.get("minScore"), "minScore");

        if (temperature == null && topK == null && minScore == null) {
            throw new IllegalArgumentException("至少需提供一個設定欄位");
        }

        if (temperature != null) {
            validateRange(temperature, TEMPERATURE_MIN, TEMPERATURE_MAX, "temperature");
            upsert(KEY_RAG_TEMPERATURE, Double.toString(temperature));
        }
        if (topK != null) {
            validateRange(topK, TOP_K_MIN, TOP_K_MAX, "topK");
            upsert(KEY_RAG_TOP_K, Integer.toString(topK));
        }
        if (minScore != null) {
            validateRange(minScore, MIN_SCORE_MIN, MIN_SCORE_MAX, "minScore");
            upsert(KEY_RAG_MIN_SCORE, Double.toString(minScore));
        }
        return getRagSettings();
    }

    /**
     * 更新 ERP AI 設定；只更新請求中有提供的欄位。
     *
     * @param body 包含 temperature、maxRounds、knowledgeTopK、knowledgeMinScore 的請求內容
     * @return 更新後的有效 Agent 設定
     * @throws IllegalArgumentException 欄位格式或範圍不合法
     */
    public AgentSettings updateAgent(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("至少需提供一個設定欄位");
        }

        Double temperature = asDouble(body.get("temperature"), "temperature");
        Integer maxRounds = asInteger(body.get("maxRounds"), "maxRounds");
        Integer knowledgeTopK = asInteger(body.get("knowledgeTopK"), "knowledgeTopK");
        Double knowledgeMinScore = asDouble(body.get("knowledgeMinScore"), "knowledgeMinScore");

        if (temperature == null && maxRounds == null && knowledgeTopK == null
                && knowledgeMinScore == null) {
            throw new IllegalArgumentException("至少需提供一個設定欄位");
        }

        if (temperature != null) {
            validateRange(temperature, TEMPERATURE_MIN, TEMPERATURE_MAX, "temperature");
            upsert(KEY_AGENT_TEMPERATURE, Double.toString(temperature));
        }
        if (maxRounds != null) {
            validateRange(maxRounds, MAX_ROUNDS_MIN, MAX_ROUNDS_MAX, "maxRounds");
            upsert(KEY_AGENT_MAX_ROUNDS, Integer.toString(maxRounds));
        }
        if (knowledgeTopK != null) {
            validateRange(knowledgeTopK, TOP_K_MIN, TOP_K_MAX, "knowledgeTopK");
            upsert(KEY_AGENT_KNOWLEDGE_TOP_K, Integer.toString(knowledgeTopK));
        }
        if (knowledgeMinScore != null) {
            validateRange(knowledgeMinScore, MIN_SCORE_MIN, MIN_SCORE_MAX, "knowledgeMinScore");
            upsert(KEY_AGENT_KNOWLEDGE_MIN_SCORE, Double.toString(knowledgeMinScore));
        }
        return getAgentSettings();
    }

    private Map<String, Object> describeValue(String key, double defaultValue, String envName,
            double min, double max) {
        String stored = readRaw(key);
        double value = defaultValue;
        String source = envPresent(envName) ? "environment" : "default";
        if (stored != null) {
            try {
                value = Double.parseDouble(stored);
                source = "database";
            } catch (NumberFormatException e) {
                log.warn("設定 {} 值格式錯誤，改用預設值: {}", key, stored);
            }
        }

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("value", value);
        description.put("source", source);
        description.put("min", min);
        description.put("max", max);
        return description;
    }

    private double readDouble(String key, double fallback) {
        String raw = readRaw(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            log.warn("設定 {} 值格式錯誤，改用預設值: {}", key, raw);
            return fallback;
        }
    }

    private int readInt(String key, int fallback) {
        String raw = readRaw(key);
        if (raw == null) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw);
            if (value != Math.rint(value)) {
                log.warn("設定 {} 必須為整數，改用預設值: {}", key, raw);
                return fallback;
            }
            return (int) value;
        } catch (NumberFormatException e) {
            log.warn("設定 {} 值格式錯誤，改用預設值: {}", key, raw);
            return fallback;
        }
    }

    private String readRaw(String key) {
        try {
            String value = jdbcTemplate.queryForObject(
                    "SELECT setting_value FROM gateway_setting WHERE setting_key = ?",
                    String.class, key);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (DataAccessException e) {
            log.warn("讀取設定 {} 失敗，改用預設值: {}", key, e.getMessage());
            return null;
        }
    }

    private void upsert(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO gateway_setting (setting_key, setting_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)",
                key, value);
    }

    private static boolean envPresent(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static Double asDouble(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必須是數字");
        }
    }

    private static Integer asInteger(Object value, String field) {
        if (value == null) {
            return null;
        }
        double number;
        if (value instanceof Number n) {
            number = n.doubleValue();
        } else {
            try {
                number = Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " 必須是整數");
            }
        }
        if (!Double.isFinite(number) || number != Math.rint(number)) {
            throw new IllegalArgumentException(field + " 必須是整數");
        }
        return (int) number;
    }

    private static void validateRange(double value, double min, double max, String field) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    field + " 必須介於 " + format(min) + " 與 " + format(max) + " 之間");
        }
    }

    private static double validTemperature(double value) {
        return Double.isFinite(value) && value >= TEMPERATURE_MIN && value <= TEMPERATURE_MAX
                ? value : 0.1;
    }

    private static int validTopK(int value) {
        return value >= TOP_K_MIN && value <= TOP_K_MAX ? value : 3;
    }

    private static int validMaxRounds(int value) {
        return value >= MAX_ROUNDS_MIN && value <= MAX_ROUNDS_MAX ? value : 8;
    }

    private static int validAgentKnowledgeTopK(int value) {
        return value >= TOP_K_MIN && value <= TOP_K_MAX ? value : 5;
    }

    private static double validMinScore(double value) {
        return Double.isFinite(value) && value >= MIN_SCORE_MIN && value <= MIN_SCORE_MAX
                ? value : 0.0;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** RAG 執行期設定。 */
    public record RagSettings(double temperature, int topK, double minScore) {
    }

    /** ERP AI 執行期設定。 */
    public record AgentSettings(double temperature, int maxRounds, int knowledgeTopK,
            double knowledgeMinScore) {
    }
}
