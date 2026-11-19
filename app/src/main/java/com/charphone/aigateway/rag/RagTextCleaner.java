package com.charphone.aigateway.rag;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * RAG 文件擷取後的文字清理。
 *
 * <p>只清理「檢索用文字」中的 HTML、Markdown 連結目標、裸 URL 與
 * percent-encoded 片段；日期、數字、單位、表格欄位與來源名稱都會保留。
 * 原始檔與原始擷取文字由 {@link RagDocumentStore} 另行保存，清理不是不可逆的。</p>
 */
@Component
public class RagTextCleaner {

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{1,200}>");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\s*\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)\\]\\s*\\([^)]*\\)");
    private static final Pattern BARE_URL = Pattern.compile(
            "(?i)(?:https?://|ftp://|www\\.)\\S+");
    private static final Pattern PERCENT_ENCODED_RUN = Pattern.compile("(?:%[0-9A-Fa-f]{2})+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern TRAILING_BLANKS = Pattern.compile("[ \\t]+$", Pattern.MULTILINE);
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");

    /**
     * 清理擷取文字，保留換行與表格列結構。
     *
     * @param raw 原始擷取文字
     * @return 清理後文字；raw 為 null 或空白時回傳空字串
     */
    public String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = SCRIPT_STYLE.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = MARKDOWN_IMAGE.matcher(text).replaceAll("$1");
        text = MARKDOWN_LINK.matcher(text).replaceAll("$1");
        text = BARE_URL.matcher(text).replaceAll(" ");
        text = PERCENT_ENCODED_RUN.matcher(text).replaceAll(" ");
        text = HtmlUtils.htmlUnescape(text);
        text = text.replace('\u00A0', ' ');
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = CONTROL_CHARS.matcher(text).replaceAll(" ");
        text = TRAILING_BLANKS.matcher(text).replaceAll("");
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }
}
