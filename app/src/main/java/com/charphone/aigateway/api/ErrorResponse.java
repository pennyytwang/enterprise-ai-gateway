package com.charphone.aigateway.api;

public record ErrorResponse(String requestId, String code, String message) {
}
