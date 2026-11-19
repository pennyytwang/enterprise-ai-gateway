package com.charphone.aigateway.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
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
 * Google Gemini（Generative Language API）實作。
 * 串流端點使用 streamGenerateContent + alt=sse；支援 function calling。
 * 由 ProviderConfig 依 gateway.provider 設定註冊（不使用 @Component，避免多供應商 bean 衝突）。
 */
public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    private final AppProperties props;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GeminiProvider(AppProperties props, WebClient geminiWebClient, ObjectMapper objectMapper) {
        this.props = props;
        this.webClient = geminiWebClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public String defaultModel() {
        return props.getGemini().getModel();
    }

    @Override
    public boolean supportsImages() {
        return true;
    }

    @Override
    public ProviderResult chat(ProviderChatRequest request) {
        String key = requireApiKey();
        String model = request.model() != null ? request.model() : props.getGemini().getModel();
        ObjectNode body = buildBody(request);

        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/models/{model}:generateContent")
                            .queryParam("key", key)
                            .build(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(props.getGemini().getReadTimeoutSeconds()))
                    .block();
        } catch (WebClientResponseException e) {
            log.error("Gemini API 錯誤 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Gemini API 錯誤: " + e.getResponseBodyAsString(), e);
        }

        if (resp == null) {
            throw new IllegalStateException("Gemini 回傳空結果");
        }
        ParsedResponse parsed = parseParts(resp);
        int promptTokens = resp.path("usageMetadata").path("promptTokenCount").asInt(0);
        int completionTokens = resp.path("usageMetadata").path("candidatesTokenCount").asInt(0);
        return new ProviderResult(parsed.text, promptTokens, completionTokens, parsed.functionCalls);
    }

    @Override
    public Flux<ProviderChunk> stream(ProviderChatRequest request) {
        String key = requireApiKey();
        String model = request.model() != null ? request.model() : props.getGemini().getModel();
        ObjectNode body = buildBody(request);

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:streamGenerateContent")
                        .queryParam("alt", "sse")
                        .queryParam("key", key)
                        .build(model))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .filter(json -> !json.isEmpty() && !"[DONE]".equals(json))
                .map(this::parseChunk)
                .timeout(Duration.ofSeconds(props.getGemini().getReadTimeoutSeconds()))
                .filter(chunk -> !chunk.text().isEmpty()
                        || chunk.promptTokens() > 0 || chunk.completionTokens() > 0);
    }

    private String requireApiKey() {
        String key = props.getGemini().getApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未設定 Gemini API Key：請設定環境變數 GEMINI_API_KEY 或 gateway.gemini.api-key-file 指向 key 檔");
        }
        return key;
    }

    private ObjectNode buildBody(ProviderChatRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        if (request.temperature() != null) {
            body.putObject("generationConfig").put("temperature", request.temperature());
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.set("systemInstruction", objectMapper.createObjectNode()
                    .set("parts", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                            .put("text", request.systemPrompt()))));
        }
        ArrayNode contents = body.putArray("contents");
        for (Turn turn : request.turns()) {
            ObjectNode content = contents.addObject();
            content.put("role", "user".equals(turn.role()) ? "user" : "model");
            ArrayNode parts = content.putArray("parts");
            if (turn.text() != null && !turn.text().isEmpty()) {
                parts.addObject().put("text", turn.text());
            }
            if (turn.images() != null) {
                for (ImagePart image : turn.images()) {
                    parts.addObject().putObject("inlineData")
                            .put("mimeType", image.mimeType())
                            .put("data", image.data());
                }
            }
            if (turn.functionCalls() != null) {
                for (FunctionCall call : turn.functionCalls()) {
                    ObjectNode part = parts.addObject();
                    ObjectNode fc = part.putObject("functionCall");
                    fc.put("name", call.name());
                    fc.set("args", objectMapper.valueToTree(call.args() == null ? Map.of() : call.args()));
                    if (call.thoughtSignature() != null && !call.thoughtSignature().isEmpty()) {
                        part.put("thoughtSignature", call.thoughtSignature());
                    }
                }
            }
            if (turn.functionResponses() != null) {
                for (FunctionResponse fr : turn.functionResponses()) {
                    ObjectNode frNode = parts.addObject().putObject("functionResponse");
                    frNode.put("name", fr.name());
                    frNode.set("response", objectMapper.valueToTree(fr.response()));
                }
            }
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("function_declarations");
            for (FunctionDeclaration decl : request.tools()) {
                ObjectNode d = declarations.addObject();
                d.put("name", decl.name());
                d.put("description", decl.description());
                d.set("parameters", objectMapper.valueToTree(decl.parameters()));
            }
        }
        return body;
    }

    private ProviderChunk parseChunk(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            ParsedResponse parsed = parseParts(node);
            JsonNode usage = node.path("usageMetadata");
            return new ProviderChunk(
                    parsed.text != null ? parsed.text : "",
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0));
        } catch (JsonProcessingException e) {
            log.warn("Gemini 串流解析失敗: {}", e.getMessage());
            return new ProviderChunk("", 0, 0);
        }
    }

    private record ParsedResponse(String text, List<FunctionCall> functionCalls) {
    }

    private ParsedResponse parseParts(JsonNode node) {
        StringBuilder text = new StringBuilder();
        List<FunctionCall> calls = new ArrayList<>();
        JsonNode candidates = node.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.hasNonNull("text")) {
                        text.append(part.get("text").asText());
                    }
                    if (part.has("functionCall")) {
                        JsonNode fc = part.get("functionCall");
                        Map<String, Object> args = objectMapper.convertValue(
                                fc.path("args"), objectMapper.getTypeFactory().constructMapType(
                                        LinkedHashMap.class, String.class, Object.class));
                        calls.add(new FunctionCall(fc.path("name").asText(), args,
                                part.hasNonNull("thoughtSignature")
                                        ? part.get("thoughtSignature").asText() : null));
                    }
                }
            }
        }
        String joined = text.toString();
        return new ParsedResponse(joined.isEmpty() ? null : joined, calls);
    }
}
