package com.charphone.aigateway.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.charphone.aigateway.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;

/**
 * OpenAI 相容端點實作（xAI / Groq / OpenRouter 等，/chat/completions 協定）。
 * 由 ProviderConfig 依 gateway.provider 設定註冊，不使用 @Component。
 * 工具呼叫以 tool_calls / role=tool 訊息往返；tool_call_id 以輪次位置產生（call_輪_序），
 * 模型呼叫與工具回饋兩側依相同規則生成即可對應。
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);
    private static final String LOG_NAME = "OpenAI-compat API";

    private final AppProperties props;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatProvider(AppProperties props, WebClient openAiCompatWebClient, ObjectMapper objectMapper) {
        this.props = props;
        this.webClient = openAiCompatWebClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        String host = props.getOpenaiCompat().getBaseUrl();
        if (host.contains("x.ai")) {
            return "xai";
        }
        if (host.contains("groq.com")) {
            return "groq";
        }
        if (host.contains("openrouter.ai")) {
            return "openrouter";
        }
        return "openai-compat";
    }

    @Override
    public String defaultModel() {
        return props.getOpenaiCompat().getModel();
    }

    @Override
    public ProviderResult chat(ProviderChatRequest request) {
        String key = requireApiKey();
        ObjectNode body = buildBody(request, false);

        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(props.getOpenaiCompat().getReadTimeoutSeconds()))
                    .block();
        } catch (WebClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            log.error("{} 錯誤 {}: {}", LOG_NAME, e.getStatusCode(), detail);
            throw new IllegalStateException(LOG_NAME + " 錯誤: " + truncate(detail), e);
        } catch (Exception e) {
            // TimeoutException / 連線失敗等 → 視為上游錯誤（502），而非 500
            log.error("{} 呼叫失敗: {}", LOG_NAME, e.toString());
            throw new IllegalStateException(LOG_NAME + " 錯誤: " + e.getClass().getSimpleName(), e);
        }

        if (resp == null) {
            throw new IllegalStateException(LOG_NAME + " 回傳空結果");
        }
        if (resp.hasNonNull("error")) {
            String detail = resp.path("error").path("message").asText(String.valueOf(resp.get("error")));
            log.error("{} 回應錯誤: {}", LOG_NAME, detail);
            throw new IllegalStateException(LOG_NAME + " 錯誤: " + truncate(detail));
        }

        JsonNode message = resp.path("choices").path(0).path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        List<FunctionCall> calls = parseToolCalls(message);
        int promptTokens = resp.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = resp.path("usage").path("completion_tokens").asInt(0);
        return new ProviderResult(text, promptTokens, completionTokens, calls);
    }

    @Override
    public Flux<ProviderChunk> stream(ProviderChatRequest request) {
        String key = requireApiKey();
        ObjectNode body = buildBody(request, true);

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !data.isBlank() && !"[DONE]".equals(data.trim()))
                .map(this::parseChunk)
                .timeout(Duration.ofSeconds(props.getOpenaiCompat().getReadTimeoutSeconds()))
                .filter(chunk -> !chunk.text().isEmpty()
                        || chunk.promptTokens() > 0 || chunk.completionTokens() > 0);
    }

    private String requireApiKey() {
        String key = props.getOpenaiCompat().getApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未設定 OpenAI 相容端點 API Key：請設定 OPENAI_COMPAT_API_KEY 或 gateway.openai-compat.api-key-file 指向 key 檔");
        }
        return key;
    }

    private ObjectNode buildBody(ProviderChatRequest request, boolean stream) {
        AppProperties.OpenAiCompat cfg = props.getOpenaiCompat();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model() != null && !request.model().isBlank()
                ? request.model() : cfg.getModel());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (stream) {
            body.put("stream", true);
            body.putObject("stream_options").put("include_usage", true);
        }

        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        int turnIndex = 0;
        int pendingCallsTurn = -1;
        for (Turn turn : request.turns()) {
            boolean hasText = turn.text() != null && !turn.text().isEmpty();
            boolean hasImages = turn.images() != null && !turn.images().isEmpty();
            if (hasText || hasImages) {
                ObjectNode message = messages.addObject();
                message.put("role", "user".equals(turn.role()) ? "user" : "assistant");
                if (hasImages) {
                    ArrayNode content = message.putArray("content");
                    if (hasText) {
                        content.addObject().put("type", "text").put("text", turn.text());
                    }
                    for (ImagePart image : turn.images()) {
                        content.addObject()
                                .put("type", "image_url")
                                .putObject("image_url")
                                .put("url", "data:" + image.mimeType() + ";base64," + image.data());
                    }
                } else {
                    message.put("content", turn.text());
                }
            }
            if (turn.functionCalls() != null && !turn.functionCalls().isEmpty()) {
                pendingCallsTurn = turnIndex;
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                ArrayNode toolCalls = assistant.putArray("tool_calls");
                int i = 0;
                for (FunctionCall call : turn.functionCalls()) {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("id", callId(turnIndex, i));
                    tc.put("type", "function");
                    tc.putObject("function")
                            .put("name", call.name())
                            .put("arguments", writeJson(call.args() == null ? Map.of() : call.args()));
                    i++;
                }
            }
            if (turn.functionResponses() != null && !turn.functionResponses().isEmpty()) {
                // tool 訊息必須引用緊接在前面的 assistant.tool_calls 之 id
                int ref = pendingCallsTurn >= 0 ? pendingCallsTurn : turnIndex;
                pendingCallsTurn = -1;
                int i = 0;
                for (FunctionResponse fr : turn.functionResponses()) {
                    ObjectNode msg = messages.addObject();
                    msg.put("role", "tool");
                    msg.put("tool_call_id", callId(ref, i));
                    msg.put("content", writeJson(fr.response()));
                    i++;
                }
            }
            turnIndex++;
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (FunctionDeclaration decl : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                t.putObject("function")
                        .put("name", decl.name())
                        .put("description", decl.description())
                        .set("parameters", objectMapper.valueToTree(decl.parameters()));
            }
        }
        return body;
    }

    /** tool_call_id：以「對話輪序_該輪內序號」生成，模型呼叫與工具回饋兩側一致。 */
    private static String callId(int turnIndex, int index) {
        return "call_" + turnIndex + "_" + index;
    }

    private List<FunctionCall> parseToolCalls(JsonNode message) {
        List<FunctionCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                JsonNode function = tc.path("function");
                Map<String, Object> args;
                try {
                    args = objectMapper.convertValue(
                            objectMapper.readTree(function.path("arguments").asText("{}")),
                            objectMapper.getTypeFactory().constructMapType(
                                    LinkedHashMap.class, String.class, Object.class));
                } catch (JsonProcessingException e) {
                    log.warn("{} tool_call arguments 解析失敗: {}", LOG_NAME, e.getMessage());
                    args = Map.of();
                }
                calls.add(new FunctionCall(function.path("name").asText(), args));
            }
        }
        return calls;
    }

    private ProviderChunk parseChunk(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode delta = node.path("choices").path(0).path("delta");
            String text = delta.path("content").isTextual() ? delta.path("content").asText() : "";
            JsonNode usage = node.path("usage");
            return new ProviderChunk(
                    text,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0));
        } catch (JsonProcessingException e) {
            log.warn("{} 串流解析失敗: {}", LOG_NAME, e.getMessage());
            return new ProviderChunk("", 0, 0);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String value) {
        return value != null && value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
