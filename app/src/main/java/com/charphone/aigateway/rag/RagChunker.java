package com.charphone.aigateway.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * RAG 文字切塊：優先保留段落與表格列，避免固定字數把表格資料切散。
 *
 * <p>表格會以「表頭 + 連續資料列」為單位打包；一般段落則在換行、
 * 句號等自然邊界切開。長段落必要時才硬切，並保留少量重疊。</p>
 */
@Component
public class RagChunker {

    private static final int MAX_CHUNK_CHARS = 600;
    private static final int MIN_CHUNK_CHARS = 80;
    private static final int BOUNDARY_LOOKBACK = 160;
    private static final int SPLIT_OVERLAP = 60;

    private static final Pattern SECTION_SEPARATOR = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern DATE_ROW = Pattern.compile(
            "^\\s*(?:\\d{1,4}[-/.年]\\d{1,2}|\\d{1,2}月\\d{1,2}日|\\d{1,2}/\\d{1,2})");

    /**
     * 將清理後文字切成 RAG chunks。
     *
     * @param text 已清理文字
     * @return chunk 清單；文字為空時回傳空清單
     */
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> rawChunks = new ArrayList<>();
        for (String section : SECTION_SEPARATOR.split(text.strip())) {
            String normalized = section.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            List<String> lines = normalized.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            if (isTable(lines)) {
                rawChunks.addAll(chunkTable(lines));
            } else {
                rawChunks.addAll(splitLongText(normalized));
            }
        }
        return mergeSmallChunks(rawChunks);
    }

    private static boolean isTable(List<String> lines) {
        if (lines.size() < 3) {
            return false;
        }
        long dateRows = lines.stream().filter(RagChunker::isDateRow).count();
        long tableRows = lines.stream().filter(RagChunker::looksLikeTableRow).count();
        if (dateRows >= 2) {
            return true;
        }
        return tableRows >= 3 && tableRows >= lines.size() - 1;
    }

    private static boolean isDateRow(String line) {
        return DATE_ROW.matcher(line).lookingAt();
    }

    private static boolean looksLikeTableRow(String line) {
        if (isDateRow(line)) {
            return true;
        }
        if (line.indexOf('\t') >= 0) {
            return line.split("\\t", -1).length >= 2;
        }
        return countChar(line, '|') >= 2;
    }

    private static int countChar(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private static List<String> chunkTable(List<String> lines) {
        int firstDataRow = 0;
        while (firstDataRow < lines.size() && !isDateRow(lines.get(firstDataRow))) {
            firstDataRow++;
        }
        if (firstDataRow >= lines.size()) {
            firstDataRow = 0;
            while (firstDataRow < lines.size() && !looksLikeTableRow(lines.get(firstDataRow))) {
                firstDataRow++;
            }
        }

        String header = String.join("\n", lines.subList(0, firstDataRow)).strip();
        List<String> rows = lines.subList(firstDataRow, lines.size());
        List<String> chunks = new ArrayList<>();
        List<String> currentRows = new ArrayList<>();

        for (String row : rows) {
            if (row.length() > MAX_CHUNK_CHARS) {
                flushTableChunk(chunks, header, currentRows);
                for (String part : splitLongText(row)) {
                    chunks.add(joinWithHeader(header, part));
                }
                continue;
            }
            if (!currentRows.isEmpty()
                    && joinWithHeader(header, String.join("\n", currentRows) + "\n" + row)
                            .length() > MAX_CHUNK_CHARS) {
                flushTableChunk(chunks, header, currentRows);
            }
            currentRows.add(row);
        }
        flushTableChunk(chunks, header, currentRows);
        return chunks;
    }

    private static void flushTableChunk(List<String> chunks, String header, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        chunks.add(joinWithHeader(header, String.join("\n", rows)));
        rows.clear();
    }

    private static String joinWithHeader(String header, String body) {
        String trimmedHeader = header == null ? "" : header.strip();
        String trimmedBody = body == null ? "" : body.strip();
        if (trimmedHeader.isEmpty()) {
            return trimmedBody;
        }
        if (trimmedBody.isEmpty()) {
            return trimmedHeader;
        }
        return trimmedHeader + "\n" + trimmedBody;
    }

    private static List<String> splitLongText(String text) {
        String remaining = text.strip();
        List<String> chunks = new ArrayList<>();
        while (remaining.length() > MAX_CHUNK_CHARS) {
            int cut = findBoundary(remaining);
            chunks.add(remaining.substring(0, cut).strip());
            int next = Math.max(0, cut - SPLIT_OVERLAP);
            remaining = remaining.substring(next).strip();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private static int findBoundary(String text) {
        int limit = Math.min(MAX_CHUNK_CHARS, text.length());
        int lowerBound = Math.max(0, limit - BOUNDARY_LOOKBACK);

        for (int i = limit; i > lowerBound; i--) {
            if (text.charAt(i - 1) == '\n') {
                return i;
            }
        }
        for (int i = limit; i > lowerBound; i--) {
            if (isSentenceBoundary(text.charAt(i - 1))) {
                return i;
            }
        }
        return limit;
    }

    private static boolean isSentenceBoundary(char value) {
        return value == '。' || value == '！' || value == '？'
                || value == '!' || value == '?' || value == '；' || value == ';'
                || value == '.';
    }

    private static List<String> mergeSmallChunks(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        for (String chunk : chunks) {
            String current = chunk == null ? "" : chunk.strip();
            if (current.isEmpty()) {
                continue;
            }
            if (!merged.isEmpty()) {
                String previous = merged.get(merged.size() - 1);
                boolean hasSmallChunk = previous.length() < MIN_CHUNK_CHARS
                        || current.length() < MIN_CHUNK_CHARS;
                if (hasSmallChunk && previous.length() + current.length() + 1 <= MAX_CHUNK_CHARS) {
                    merged.set(merged.size() - 1, previous + "\n" + current);
                    continue;
                }
            }
            merged.add(current);
        }
        return merged;
    }
}
