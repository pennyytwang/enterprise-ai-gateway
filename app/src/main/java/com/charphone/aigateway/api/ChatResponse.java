package com.charphone.aigateway.api;

/**
 * POST /api/v1/chat 非串流回覆。
 */
public record ChatResponse(
        String requestId,
        String sessionId,
        String message,
        UsageDto usage) {
}
