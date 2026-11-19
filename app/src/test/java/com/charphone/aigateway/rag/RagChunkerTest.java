package com.charphone.aigateway.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RagChunkerTest {

    private final RagChunker chunker = new RagChunker();

    @Test
    void keepsWeatherTableRowsTogetherWithHeader() {
        StringBuilder text = new StringBuilder("2025年11月台北每日天氣與氣溫\n\n"
                + "日期\t天氣概況\t溫度範圍\n");
        for (int day = 1; day <= 30; day++) {
            text.append(String.format("11/%02d\t多雲時晴\t%d°C ~ %d°C%n",
                    day, 17 + day % 5, 22 + day % 6));
        }

        List<String> chunks = chunker.chunk(text.toString());

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 600));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("11/04")));
        assertTrue(chunks.stream()
                .filter(chunk -> chunk.contains("11/04"))
                .allMatch(chunk -> chunk.contains("日期\t天氣概況\t溫度範圍")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("日期\t天氣概況\t溫度範圍")));
    }

    @Test
    void splitsLongParagraphAtNaturalBoundary() {
        String sentence = "這是一段用來測試切塊邊界的文字，包含日期 11/04 與溫度 20°C ~ 23°C。";
        String text = sentence.repeat(20);

        List<String> chunks = chunker.chunk(text);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 600));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.contains("11/04")));
    }
}
