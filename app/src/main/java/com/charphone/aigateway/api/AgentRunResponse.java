package com.charphone.aigateway.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunResponse(
        String requestId,
        String sessionId,
        String message,
        List<ToolCallInfo> toolCalls,
        UsageDto usage) {

    public record ToolCallInfo(String tool, boolean ok, long latencyMs) {
    }
}
