package com.charphone.aigateway.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 檢索結果重排：在向量相似度之外，加入日期與詞彙訊號。
 *
 * <p>固定字數切塊常把表格資料與來源 URL 混在一起，單靠 embedding 容易讓
 * 「提到天氣」的片段排在真正包含指定日期的表格前面。此類別會先取出較大的
 * 候選集，再依日期是否出現在行首、日期是否出現於片段、以及查詢詞彙覆蓋率
 * 重新排序。</p>
 */
@Component
public class RetrievalRanker {

    private static final double DATE_LINE_BONUS = 0.25;
    private static final double DATE_ANYWHERE_BONUS = 0.08;
    private static final double LEXICAL_BONUS_WEIGHT = 0.10;
    private static final double MAX_SCORE = 1.0;
    private static final double MIN_SCORE = 0.0;
    private static final double SCORE_PRECISION = 1000.0;

    private static final Pattern MARKDOWN_LINK_TARGET = Pattern.compile("\\]\\([^)]*\\)");
    private static final Pattern URL_PATTERN = Pattern.compile("(?:https?://|www\\.)\\S+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_ENCODED = Pattern.compile("%[0-9A-Fa-f]{2}");
    private static final Pattern LEXICAL_SEPARATORS = Pattern.compile("[\\p{P}\\s]+");
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:(?<isoYear>\\d{4})[-/.](?<isoMonth>\\d{1,2})[-/.](?<isoDay>\\d{1,2})"
                    + "|(?<month>\\d{1,2})[/-](?<day>\\d{1,2})"
                    + "|(?<cnMonth>\\d{1,2})月(?<cnDay>\\d{1,2})日)(?!\\d)");

    /**
     * 重新排序 dense retrieval 的候選片段。
     *
     * @param query 使用者查詢
     * @param hits  已含 denseScore 的候選片段，順序需為 dense 分數由高到低
     * @param topK  最終要回傳的片段數
     * @return 排序後的新片段清單，不修改傳入的 maps
     */
    public List<Map<String, Object>> rank(String query, List<Map<String, Object>> hits, int topK) {
        if (hits == null || hits.isEmpty() || topK <= 0) {
            return List.of();
        }

        Set<DateToken> queryDates = extractDates(query);
        Set<String> queryBigrams = bigrams(normalizeLexical(query));
        List<RankedHit> ranked = new ArrayList<>(hits.size());

        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> hit = hits.get(i);
            String text = asString(hit.get("text"));
            String strippedText = stripNoise(text);
            double denseScore = readDenseScore(hit);

            boolean dateAtLineStart = !queryDates.isEmpty()
                    && hasDateAtLineStart(strippedText, queryDates);
            boolean dateAnywhere = !queryDates.isEmpty()
                    && containsDate(strippedText, queryDates);
            double lexicalScore = bigramCoverage(queryBigrams, normalizeLexical(text));

            double finalScore = denseScore;
            if (dateAtLineStart) {
                finalScore += DATE_LINE_BONUS;
            } else if (dateAnywhere) {
                finalScore += DATE_ANYWHERE_BONUS;
            }
            finalScore += LEXICAL_BONUS_WEIGHT * lexicalScore;
            finalScore = clampScore(finalScore);

            Map<String, Object> rankedHit = new LinkedHashMap<>(hit);
            rankedHit.put("denseScore", roundScore(denseScore));
            rankedHit.put("score", roundScore(finalScore));
            ranked.add(new RankedHit(rankedHit, dateAtLineStart, dateAnywhere, finalScore, denseScore, i));
        }

        ranked.sort(Comparator
                .comparing((RankedHit hit) -> hit.dateAtLineStart(), Comparator.reverseOrder())
                .thenComparing((RankedHit hit) -> hit.dateAnywhere(), Comparator.reverseOrder())
                .thenComparing(Comparator.comparingDouble(RankedHit::finalScore).reversed())
                .thenComparing(Comparator.comparingDouble(RankedHit::denseScore).reversed())
                .thenComparingInt(RankedHit::denseRank));

        int limit = Math.min(topK, ranked.size());
        List<Map<String, Object>> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(ranked.get(i).hit());
        }
        return result;
    }

    private static double readDenseScore(Map<String, Object> hit) {
        Object denseScore = hit.get("denseScore");
        if (denseScore instanceof Number number) {
            return number.doubleValue();
        }
        Object score = hit.get("score");
        return score instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static Set<DateToken> extractDates(String query) {
        Set<DateToken> dates = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return dates;
        }
        Matcher matcher = DATE_PATTERN.matcher(query);
        while (matcher.find()) {
            DateToken token = parseDate(matcher);
            if (token != null) {
                dates.add(token);
            }
        }
        return dates;
    }

    private static DateToken parseDate(Matcher matcher) {
        Integer month;
        Integer day;
        if (matcher.group("isoYear") != null) {
            month = parseInteger(matcher.group("isoMonth"));
            day = parseInteger(matcher.group("isoDay"));
        } else if (matcher.group("cnMonth") != null) {
            month = parseInteger(matcher.group("cnMonth"));
            day = parseInteger(matcher.group("cnDay"));
        } else {
            month = parseInteger(matcher.group("month"));
            day = parseInteger(matcher.group("day"));
        }
        if (month == null || day == null || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return new DateToken(month, day);
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasDateAtLineStart(String text, Set<DateToken> queryDates) {
        for (String line : text.split("\\R")) {
            String candidate = stripListMarker(line.stripLeading());
            if (candidate.isEmpty()) {
                continue;
            }
            Matcher matcher = DATE_PATTERN.matcher(candidate);
            if (matcher.lookingAt()) {
                DateToken token = parseDate(matcher);
                if (token != null && queryDates.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripListMarker(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
            return line.substring(2).stripLeading();
        }
        return line;
    }

    private static boolean containsDate(String text, Set<DateToken> queryDates) {
        Matcher matcher = DATE_PATTERN.matcher(text);
        while (matcher.find()) {
            DateToken token = parseDate(matcher);
            if (token != null && queryDates.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String stripNoise(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = MARKDOWN_LINK_TARGET.matcher(text).replaceAll(" ");
        cleaned = URL_PATTERN.matcher(cleaned).replaceAll(" ");
        return PERCENT_ENCODED.matcher(cleaned).replaceAll(" ");
    }

    private static String normalizeLexical(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = LEXICAL_SEPARATORS.matcher(stripNoise(text).toLowerCase(Locale.ROOT))
                .replaceAll("");
        return cleaned;
    }

    private static Set<String> bigrams(String text) {
        if (text.length() < 2) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }

    private static double bigramCoverage(Set<String> queryBigrams, String candidate) {
        if (queryBigrams.isEmpty() || candidate.isEmpty()) {
            return 0.0;
        }
        int matches = 0;
        for (String bigram : queryBigrams) {
            if (candidate.contains(bigram)) {
                matches++;
            }
        }
        return (double) matches / queryBigrams.size();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double clampScore(double score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private static double roundScore(double score) {
        return Math.round(score * SCORE_PRECISION) / SCORE_PRECISION;
    }

    private record DateToken(int month, int day) {
    }

    private record RankedHit(Map<String, Object> hit, boolean dateAtLineStart, boolean dateAnywhere,
            double finalScore, double denseScore, int denseRank) {
    }
}
