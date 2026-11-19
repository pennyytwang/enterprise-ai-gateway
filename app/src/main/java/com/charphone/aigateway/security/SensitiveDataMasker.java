package com.charphone.aigateway.security;

import java.util.regex.Pattern;

/**
 * 敏感資料遮罩：身分證字號、銀行帳號、電話號碼。
 * 用於稽核日誌寫入前，避免敏感資訊落 DB。
 */
public final class SensitiveDataMasker {

    private static final Pattern TAIWAN_ID = Pattern.compile("(?<![A-Za-z0-9])[A-Z][\\d]{9}(?![A-Za-z0-9])");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("(?<!\\d)\\d{10,14}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("0\\d{1,3}-?\\d{6,8}");

    private SensitiveDataMasker() {
    }

    /** 遮蔽所有敏感資料型別。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = TAIWAN_ID.matcher(result).replaceAll(m -> {
            String match = m.group();
            return match.charAt(0) + "****";
        });
        result = BANK_ACCOUNT.matcher(result).replaceAll("************");
        result = PHONE.matcher(result).replaceAll("0****");
        return result;
    }
}
