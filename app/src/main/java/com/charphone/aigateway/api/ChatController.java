package com.charphone.aigateway.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.charphone.aigateway.audit.AppUserContext;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.security.ApiKeyFilter;
import com.charphone.aigateway.service.ChatService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        ApiClient client = (ApiClient) httpRequest.getAttribute(ApiKeyFilter.ATTR_CLIENT);
        return chatService.chat(request, client, AppUserContext.from(httpRequest));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        ApiClient client = (ApiClient) httpRequest.getAttribute(ApiKeyFilter.ATTR_CLIENT);
        return chatService.stream(request, client, AppUserContext.from(httpRequest));
    }
}
