package com.charphone.aigateway.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RetrievalRankerTest {

    private static final String URL_HEAVY_CHUNK = "你可以前往 Weather Spark 台北歷史天氣，或利用 AccuWeather "
            + "台北歷史天氣查詢更完整的每日數據。 [1] "
            + "(https://tw.weatherspark.com/h/m/137170/2025/11/"
            + "%E5%8F%B0%E5%8C%97%E5%B8%82%E3%80%81%E5%8F%B0%E7%81%A32025%E5%B9%B411%E6%9C%88"
            + "%E6%AD%B7%E5%8F%B2%E5%A4%A9%E6%B0%A3)";

    private static final String WEATHER_TABLE_CHUNK = "2025年11月台北每日天氣與氣溫\n\n"
            + "日期\t天氣概況\t溫度範圍\n"
            + "11/01\t多雲\t21°C ~ 24°C\n"
            + "11/04\t小雨\t20°C ~ 23°C\n"
            + "11/05\t小雨\t22°C ~ 24°C\n";

    private static final String WEATHER_RANGE_CHUNK = "2025年11月台北每日天氣與氣溫\n\n"
            + "11/26~11/30\t多雲時晴\t17°C ~ 23°C\n";

    private final RetrievalRanker ranker = new RetrievalRanker();

    @Test
    void dateQueryRanksMatchingTableRowBeforeHigherDenseUrlChunk() {
        List<Map<String, Object>> hits = List.of(
                hit(1, URL_HEAVY_CHUNK, 0.88),
                hit(2, WEATHER_TABLE_CHUNK, 0.82));

        List<Map<String, Object>> ranked = ranker.rank("11/04天氣", hits, 3);

        assertEquals(2, ranked.get(0).get("chunkIndex"));
        assertEquals(0.82, (double) ranked.get(0).get("denseScore"));
        assertTrue((double) ranked.get(0).get("score") > 0.82);
    }

    @Test
    void chineseDateQueryUsesSameDateSignal() {
        List<Map<String, Object>> hits = List.of(
                hit(1, URL_HEAVY_CHUNK, 0.88),
                hit(2, WEATHER_TABLE_CHUNK, 0.82));

        List<Map<String, Object>> ranked = ranker.rank("11月4日天氣", hits, 3);

        assertEquals(2, ranked.get(0).get("chunkIndex"));
    }

    @Test
    void dateRangeQueryMatchesEitherEndpoint() {
        List<Map<String, Object>> hits = List.of(
                hit(1, URL_HEAVY_CHUNK, 0.88),
                hit(2, WEATHER_TABLE_CHUNK, 0.82),
                hit(3, WEATHER_RANGE_CHUNK, 0.80));

        List<Map<String, Object>> ranked = ranker.rank("11/26~11/30天氣", hits, 3);

        assertEquals(3, ranked.get(0).get("chunkIndex"));
    }

    @Test
    void nonDateQueryKeepsDenseOrderWhenThereIsNoLexicalSignal() {
        List<Map<String, Object>> hits = List.of(
                hit(1, URL_HEAVY_CHUNK, 0.88),
                hit(2, WEATHER_TABLE_CHUNK, 0.82));

        List<Map<String, Object>> ranked = ranker.rank("訂單", hits, 3);

        assertEquals(1, ranked.get(0).get("chunkIndex"));
    }

    @Test
    void topKLimitsReturnedCandidates() {
        List<Map<String, Object>> hits = List.of(
                hit(1, URL_HEAVY_CHUNK, 0.88),
                hit(2, WEATHER_TABLE_CHUNK, 0.82));

        List<Map<String, Object>> ranked = ranker.rank("11/04天氣", hits, 1);

        assertEquals(1, ranked.size());
        assertEquals(2, ranked.get(0).get("chunkIndex"));
    }

    private static Map<String, Object> hit(int chunkIndex, String text, double denseScore) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("chunkIndex", chunkIndex);
        hit.put("text", text);
        hit.put("denseScore", denseScore);
        return hit;
    }
}
