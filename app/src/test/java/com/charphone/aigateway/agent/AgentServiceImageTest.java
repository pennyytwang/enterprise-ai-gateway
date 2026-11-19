package com.charphone.aigateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.charphone.aigateway.api.AgentRunRequest;
import com.charphone.aigateway.audit.AuditLogDao;
import com.charphone.aigateway.audit.AuditLogDao.AuditRecord;
import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider;
import com.charphone.aigateway.provider.LlmProvider.ImagePart;
import com.charphone.aigateway.provider.LlmProvider.ProviderChatRequest;
import com.charphone.aigateway.provider.LlmProvider.ProviderResult;
import com.charphone.aigateway.security.ApiClient;
import com.charphone.aigateway.settings.GatewaySettingsService;
import com.charphone.aigateway.settings.GatewaySettingsService.AgentSettings;
import com.charphone.aigateway.tools.ToolRegistry;

@ExtendWith(MockitoExtension.class)
class AgentServiceImageTest {

    @Mock
    private LlmProvider provider;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AuditLogDao auditLogDao;

    @Mock
    private GatewaySettingsService settingsService;

    private AgentService service;
    private final ApiClient client = new ApiClient(1, "test", "hash", true, "USER", 1000, 100);

    @BeforeEach
    void setUp() {
        lenient().when(settingsService.getAgentSettings())
                .thenReturn(new AgentSettings(0.2, 8, 5, 0.0));
        service = new AgentService(provider, toolRegistry, auditLogDao, new AppProperties(),
                settingsService);
    }

    @Test
    void imageIsPassedToProviderAndOnlyCountIsAudited() {
        when(toolRegistry.declarations()).thenReturn(List.of());
        when(provider.chat(any())).thenReturn(new ProviderResult("完成", 1, 2, List.of()));
        when(provider.name()).thenReturn("gemini");

        String data = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });
        AgentRunRequest request = new AgentRunRequest(null, "這是什麼", 8,
                List.of(new AgentRunRequest.ImageInput("image/jpg", "data:image/jpeg;base64," + data)));

        service.run(request, client);

        ArgumentCaptor<ProviderChatRequest> requestCaptor = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(provider).chat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().turns().get(0).images())
                .containsExactly(new ImagePart("image/jpeg", data));
        assertThat(requestCaptor.getValue().temperature()).isEqualTo(0.2);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLogDao).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().prompt()).isEqualTo("這是什麼 [附圖 1 張]");
        assertThat(auditCaptor.getValue().prompt()).doesNotContain(data);
    }

    @Test
    void rejectUnsupportedImageType() {
        AgentRunRequest request = new AgentRunRequest(null, "看圖", 8,
                List.of(new AgentRunRequest.ImageInput("image/gif", "AQID")));

        assertThatThrownBy(() -> service.run(request, client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    void rejectMoreThanThreeImages() {
        AgentRunRequest.ImageInput image = new AgentRunRequest.ImageInput("image/png", "AQID");
        AgentRunRequest request = new AgentRunRequest(null, "看圖", 8,
                List.of(image, image, image, image));

        assertThatThrownBy(() -> service.run(request, client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3");
    }
}
