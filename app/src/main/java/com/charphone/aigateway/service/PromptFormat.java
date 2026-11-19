package com.charphone.aigateway.service;

public final class PromptFormat {

    public static final String MARKDOWN_RULES = """
            - 回答請使用 GitHub Flavored Markdown 排版。
            - 若資料包含 2 個以上欄位且 2 筆以上紀錄，請使用 Markdown 表格呈現，表格前後保留空行。
            - 表格欄位名稱使用繁體中文；金額使用千分位並標示 TWD，比率或百分比請明確標示。
            - 不要輸出 HTML 標籤；不要將整段回答包在 code fence。
            """;

    private PromptFormat() {
    }
}
