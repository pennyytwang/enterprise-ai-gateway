package com.charphone.aigateway.api;

public record UsageDto(String provider, String model, int promptTokens, int completionTokens, int totalTokens) {
}
