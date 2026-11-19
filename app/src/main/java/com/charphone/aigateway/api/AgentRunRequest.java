package com.charphone.aigateway.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRunRequest(
        String sessionId,
        @NotBlank(message = "userMessage 不可為空") String userMessage,
        Integer maxToolRounds,
        @Size(max = 3, message = "一次最多附上 3 張圖片") List<@Valid ImageInput> images) {

    /** 使用者附上的圖片；data 為不含 data URL prefix 的 base64。 */
    public record ImageInput(
            @NotBlank(message = "圖片類型不可為空") String mimeType,
            @NotBlank(message = "圖片內容不可為空") String data) {
    }
}
