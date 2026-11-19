package com.charphone.aigateway.security;

public record ApiClient(int id, String clientName, String apiKeyHash, boolean enabled, String role,
        int rateLimitDaily, int rateLimitPerMin) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
