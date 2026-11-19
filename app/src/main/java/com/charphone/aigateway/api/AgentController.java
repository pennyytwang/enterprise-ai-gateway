package com.charphone.aigateway.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charphone.aigateway.agent.AgentService;
import com.charphone.aigateway.audit.AppUserContext;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.security.ApiKeyFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/agent/run")
    public AgentRunResponse run(@Valid @RequestBody AgentRunRequest request, HttpServletRequest httpRequest) {
        ApiClient client = (ApiClient) httpRequest.getAttribute(ApiKeyFilter.ATTR_CLIENT);
        return agentService.run(request, client, AppUserContext.from(httpRequest));
    }
}
