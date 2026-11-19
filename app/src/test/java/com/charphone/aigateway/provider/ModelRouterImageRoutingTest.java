package com.charphone.aigateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.charphone.aigateway.provider.LlmProvider.ImagePart;
import com.charphone.aigateway.provider.LlmProvider.ProviderChatRequest;
import com.charphone.aigateway.provider.LlmProvider.ProviderResult;
import com.charphone.aigateway.provider.LlmProvider.Turn;

class ModelRouterImageRoutingTest {

    @Test
    void imageRequestSkipsTextOnlyProviderAndUsesVisionProviderModel() {
        LlmProvider textOnly = mock(LlmProvider.class);
        when(textOnly.name()).thenReturn("openrouter");
        when(textOnly.supportsImages()).thenReturn(false);

        LlmProvider vision = mock(LlmProvider.class);
        when(vision.name()).thenReturn("gemini");
        when(vision.supportsImages()).thenReturn(true);
        when(vision.defaultModel()).thenReturn("gemini-3.6-flash");
        when(vision.chat(any())).thenReturn(new ProviderResult("ok", 1, 1, List.of()));

        ModelRouter router = new ModelRouter(List.of(textOnly, vision));
        ProviderChatRequest request = new ProviderChatRequest(
                "openrouter-text-model", "system",
                List.of(Turn.user("看圖", List.of(new ImagePart("image/png", "AQID")))), List.of());

        ProviderResult result = router.chat(request);

        assertThat(result.text()).isEqualTo("ok");
        verify(textOnly, never()).chat(any());
        ArgumentCaptor<ProviderChatRequest> captor = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(vision).chat(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gemini-3.6-flash");
        assertThat(captor.getValue().turns().get(0).images()).hasSize(1);
    }

    @Test
    void textFallbackUsesFallbackProviderModel() {
        LlmProvider primary = mock(LlmProvider.class);
        when(primary.name()).thenReturn("openrouter");
        when(primary.chat(any())).thenThrow(new IllegalStateException("down"));

        LlmProvider fallback = mock(LlmProvider.class);
        when(fallback.name()).thenReturn("gemini");
        when(fallback.defaultModel()).thenReturn("gemini-3.6-flash");
        when(fallback.chat(any())).thenReturn(new ProviderResult("ok", 1, 1, List.of()));

        ModelRouter router = new ModelRouter(List.of(primary, fallback));
        ProviderChatRequest request = new ProviderChatRequest(
                "openrouter-text-model", "system", List.of(Turn.user("hi")), List.of());

        router.chat(request);

        ArgumentCaptor<ProviderChatRequest> captor = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fallback).chat(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gemini-3.6-flash");
    }
}
