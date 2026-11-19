package com.charphone.aigateway.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RagTextCleanerTest {

    private final RagTextCleaner cleaner = new RagTextCleaner();

    @Test
    void removesUrlNoiseButKeepsSourceNameAndDateRow() {
        String raw = "2025年11月台北每日天氣與氣溫\n\n"
                + "日期\t天氣概況\t溫度範圍\n"
                + "11/04\t小雨\t20°C ~ 23°C\n"
                + "資料來源：Weather Spark [1] "
                + "(https://tw.weatherspark.com/h/m/137170/2025/11/"
                + "%E5%8F%B0%E5%8C%97%E5%B8%82%E3%80%81%E5%8F%B0%E7%81%A3"
                + "%E6%AD%B7%E5%8F%B2%E5%A4%A9%E6%B0%A3)\n";

        String cleaned = cleaner.clean(raw);

        assertTrue(cleaned.contains("11/04\t小雨\t20°C ~ 23°C"));
        assertTrue(cleaned.contains("Weather Spark"));
        assertFalse(cleaned.contains("http"));
        assertFalse(cleaned.contains("%E5"));
    }

    @Test
    void stripsHtmlAndMarkdownLinkTargets() {
        String raw = "<p>請參考 <a href=\"https://example.com/a\">測試文件</a></p>"
                + "[下載連結](https://example.com/file.pdf)";

        String cleaned = cleaner.clean(raw);

        assertTrue(cleaned.contains("請參考"));
        assertTrue(cleaned.contains("測試文件"));
        assertTrue(cleaned.contains("下載連結"));
        assertFalse(cleaned.contains("example.com"));
    }
}
