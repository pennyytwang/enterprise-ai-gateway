package com.charphone.aigateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.charphone.aigateway.config.AppProperties;
import com.charphone.aigateway.provider.LlmProvider.ImagePart;
import com.charphone.aigateway.provider.LlmProvider.ProviderChatRequest;
import com.charphone.aigateway.provider.LlmProvider.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ProviderImagePayloadTest {

    private static final String DATA = "AQID";
    private static final ProviderChatRequest REQUEST = new ProviderChatRequest(
            "test-model", "system", List.of(Turn.user("看圖", List.of(new ImagePart("image/png", DATA)))),
            List.of());

    @Test
    void geminiUsesInlineDataParts() throws Exception {
        GeminiProvider provider = new GeminiProvider(new AppProperties(), null, new ObjectMapper());

        ObjectNode body = invoke(provider, "buildBody",
                new Class<?>[] { ProviderChatRequest.class }, REQUEST);

        var parts = body.path("contents").get(0).path("parts");
        assertThat(parts.get(0).path("text").asText()).isEqualTo("看圖");
        assertThat(parts.get(1).path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(parts.get(1).path("inlineData").path("data").asText()).isEqualTo(DATA);
    }

    @Test
    void openAiCompatUsesImageUrlContent() throws Exception {
        OpenAiCompatProvider provider = new OpenAiCompatProvider(new AppProperties(), null, new ObjectMapper());

        ObjectNode body = invoke(provider, "buildBody",
                new Class<?>[] { ProviderChatRequest.class, boolean.class }, REQUEST, false);

        var content = body.path("messages").get(1).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64," + DATA);
    }

    private static ObjectNode invoke(Object target, String methodName, Class<?>[] parameterTypes,
            Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (ObjectNode) method.invoke(target, args);
    }
}
