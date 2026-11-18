package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tactical.core.api.ApiException;
import com.tactical.core.config.TacticalProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Every call to the model host goes through here, on the OpenAI-compatible surface:
 * GET /v1/models, POST /v1/embeddings, POST /v1/chat/completions. Pointing the base URL at
 * another OpenAI-style host is the only change needed to switch providers.
 */
@Component
public class OllamaClient {

    public record ChatStream(String text, int tokens) {
    }

    private final TacticalProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Builds the client with the configured connect timeout. */
    public OllamaClient(TacticalProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        Duration connect = props.ollama().connectTimeout() == null
                ? Duration.ofSeconds(5)
                : props.ollama().connectTimeout();
        this.http = HttpClient.newBuilder().connectTimeout(connect).build();
    }

    /** Base URL of the OpenAI-compatible host, without a trailing slash. */
    private String base() {
        return props.ollama().baseUrl().replaceAll("/+$", "");
    }

    /** Per-request timeout, generous by default because a local model can be slow. */
    private Duration readTimeout() {
        return props.ollama().readTimeout() == null ? Duration.ofSeconds(180) : props.ollama().readTimeout();
    }

    /** A request builder with the JSON accept header and the bearer token already set. */
    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(readTimeout())
                .header("Accept", "application/json");
        String key = props.ollama().apiKey();
        if (key != null && !key.isBlank()) {
            builder.header("Authorization", "Bearer " + key);
        }
        return builder;
    }

    /** Model ids the host serves, read from the OpenAI models endpoint. */
    public List<String> listModels() {
        JsonNode json = send("GET", "/models", null);
        List<String> names = new ArrayList<>();
        for (JsonNode item : json.path("data")) {
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                names.add(id);
            }
        }
        return names;
    }

    /** One vector for one text; a missing vector is an error, not an empty result. */
    public double[] embed(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.ollama().embeddingModel());
        body.put("input", text);
        JsonNode json = send("POST", "/embeddings", body);
        JsonNode vector = json.path("data").path(0).path("embedding");
        if (!vector.isArray() || vector.isEmpty()) {
            throw new ApiException(502, "The embedding host returned no vector - is "
                    + props.ollama().embeddingModel() + " pulled?");
        }
        double[] values = new double[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            values[i] = vector.get(i).asDouble();
        }
        return values;
    }

    /** One non-streaming completion; jsonMode asks the host to answer with a JSON object. */
    public String chat(List<Map<String, String>> messages, String model, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        JsonNode json = send("POST", "/chat/completions", body);
        String content = json.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new ApiException(502, "The model returned an empty answer");
        }
        return content;
    }

    /**
     * Reads the SSE stream the host writes and hands every content delta to the consumer the
     * moment it arrives, so a caller can forward it to a browser without buffering the answer.
     */
    public ChatStream streamChat(List<Map<String, String>> messages, String model, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        HttpRequest request = request("/chat/completions")
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();

        StringBuilder answer = new StringBuilder();
        AtomicInteger tokens = new AtomicInteger();
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 300) {
                throw new ApiException(response.statusCode(),
                        "The model host refused the stream (HTTP " + response.statusCode() + ")");
            }
            try (Stream<String> lines = response.body()) {
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next().trim();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode chunk = mapper.readTree(data);
                    String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        answer.append(delta);
                        onDelta.accept(delta);
                    }
                    int reported = chunk.path("usage").path("completion_tokens").asInt(0);
                    if (reported > 0) {
                        tokens.set(reported);
                    }
                }
            }
        } catch (IOException error) {
            throw new ApiException(502, "The model stream failed: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "The model stream was interrupted");
        }
        String text = answer.toString();
        int count = tokens.get() > 0 ? tokens.get() : text.split("\\s+").length;
        return new ChatStream(text, count);
    }

    /** The shared non-streaming path: status check, readable failure, parsed body. */
    private JsonNode send(String method, String path, Object body) {
        HttpRequest.Builder builder = request(path)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank()
                    ? null
                    : mapper.readTree(response.body());
            if (response.statusCode() >= 300) {
                String reason = json == null ? "" : json.path("error").path("message").asText("");
                throw new ApiException(response.statusCode(), "Model host HTTP " + response.statusCode()
                        + (reason.isBlank() ? "" : " - " + reason));
            }
            if (json == null) {
                throw new ApiException(502, "The model host returned an empty body");
            }
            return json;
        } catch (IOException error) {
            throw new ApiException(502, "Model host unreachable at " + base() + ": " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "The model call was interrupted");
        }
    }

    /** Serialises a request body once, with one readable failure if it cannot be written. */
    private String write(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException error) {
            throw new ApiException(500, "Could not serialise the request body");
        }
    }
}
