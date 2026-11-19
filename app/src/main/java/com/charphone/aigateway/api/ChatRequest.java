package com.charphone.aigateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/chat 請求。
 */
public record ChatRequest(
        @Size(max = 128) String sessionId,
        @NotBlank @Size(max = 20000) String userMessage,
        @Size(max = 20000) String systemPrompt,
        String model,
        boolean stream) {
}
