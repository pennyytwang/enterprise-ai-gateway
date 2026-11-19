package com.charphone.aigateway.service;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.charphone.aigateway.api.ChatRequest;
import com.charphone.aigateway.api.ChatResponse;
import com.charphone.aigateway.api.UsageDto;
import com.charphone.aigateway.audit.AppUserContext;
import com.charphone.aigateway.audit.AuditLogDao;
import com.charphone.aigateway.audit.AuditLogDao.AuditRecord;
import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.LlmProvider.ProviderChatRequest;
import com.charphone.aigateway.provider.LlmProvider.ProviderResult;
import com.charphone.aigateway.provider.LlmProvider.Turn;
import com.charphone.aigateway.security.ApiClient;

/**
 * 對話編排：組 prompt → 呼叫供應商 → 寫稽核 → 回傳。
 */
@Service
public class ChatService {

    private final LlmProvider provider;
    private final AuditLogDao auditLogDao;
    private final AppProperties props;

    public ChatService(LlmProvider provider, AuditLogDao auditLogDao, AppProperties props) {
        this.provider = provider;
        this.auditLogDao = auditLogDao;
        this.props = props;
    }

    public ChatResponse chat(ChatRequest request, ApiClient client) {
        return chat(request, client, AppUserContext.EMPTY);
    }

    public ChatResponse chat(ChatRequest request, ApiClient client, AppUserContext appUser) {
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        String model = effectiveModel(request.model());

        ProviderChatRequest providerRequest = toProviderRequest(request, model);
        ProviderResult result = provider.chat(providerRequest);

        long latency = System.currentTimeMillis() - start;
        auditLogDao.record(new AuditRecord(
                requestId, client.id(), client.clientName(), appUser.userId(), appUser.username(),
                appUser.sessionId(), "/api/v1/chat", model, false,
                request.userMessage(), result.text(), result.promptTokens(), result.completionTokens(),
                latency, 200));

        UsageDto usage = new UsageDto(provider.name(), model,
                result.promptTokens(), result.completionTokens(),
                result.promptTokens() + result.completionTokens());

        return new ChatResponse(requestId, request.sessionId(), result.text(), usage);
    }

    public SseEmitter stream(ChatRequest request, ApiClient client) {
        return stream(request, client, AppUserContext.EMPTY);
    }

    public SseEmitter stream(ChatRequest request, ApiClient client, AppUserContext appUser) {
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        String model = effectiveModel(request.model());

        SseEmitter emitter = new SseEmitter(0L);
        ProviderChatRequest providerRequest = toProviderRequest(request, model);

        StringBuilder accumulated = new StringBuilder();
        int[] usage = {0, 0};

        provider.stream(providerRequest).subscribe(
                chunk -> {
                    accumulated.append(chunk.text());
                    if (chunk.promptTokens() > 0) {
                        usage[0] = chunk.promptTokens();
                    }
                    if (chunk.completionTokens() > 0) {
                        usage[1] = chunk.completionTokens();
                    }
                    try {
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(java.util.Map.of("delta", chunk.text()), MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(java.util.Map.of("message", error.getMessage()), MediaType.APPLICATION_JSON));
                    } catch (Exception ignore) {
                        // 已斷線
                    }
                    emitter.complete();
                    writeAudit(requestId, client, appUser, request, model, accumulated.toString(), usage[0], usage[1],
                            start, 500);
                },
                () -> {
                    try {
                        emitter.send(SseEmitter.event().name("usage")
                                .data(java.util.Map.of(
                                        "provider", provider.name(),
                                        "model", model,
                                        "promptTokens", usage[0],
                                        "completionTokens", usage[1]),
                                        MediaType.APPLICATION_JSON));
                        emitter.send(SseEmitter.event().name("done").data(""));
                    } catch (Exception ignore) {
                        // 已斷線
                    }
                    emitter.complete();
                    writeAudit(requestId, client, appUser, request, model, accumulated.toString(), usage[0], usage[1],
                            start, 200);
                });

        return emitter;
    }

    private ProviderChatRequest toProviderRequest(ChatRequest request, String model) {
        return new ProviderChatRequest(
                model,
                request.systemPrompt(),
                List.of(Turn.user(request.userMessage())),
                null);
    }

    private String effectiveModel(String requested) {
        return requested != null && !requested.isBlank() ? requested : props.getActiveModel();
    }

    private void writeAudit(String requestId, ApiClient client, AppUserContext appUser, ChatRequest request,
            String model, String response, int promptTokens, int completionTokens, long start, int statusCode) {
        auditLogDao.record(new AuditRecord(
                requestId, client.id(), client.clientName(), appUser.userId(), appUser.username(),
                appUser.sessionId(), "/api/v1/chat", model, true,
                request.userMessage(), response, promptTokens, completionTokens,
                System.currentTimeMillis() - start, statusCode));
    }
}
