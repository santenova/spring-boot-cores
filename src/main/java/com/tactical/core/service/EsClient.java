package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tactical.core.api.ApiException;
import com.tactical.core.config.TacticalProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch over its REST API. No vendor client on the classpath: the queries this port
 * sends are the same JSON the browser app builds, and raw HTTP keeps that visible.
 */
@Component
public class EsClient {

    public record EsResponse(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final TacticalProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /** Binds the configuration and builds one reusable HTTP client for the whole app. */
    public EsClient(TacticalProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** The configured cluster to talk to, selected by label with the first entry as fallback. */
    public TacticalProperties.Endpoint activeEndpoint() {
        List<TacticalProperties.Endpoint> endpoints = props.elastic().endpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new ApiException(500, "No Elasticsearch endpoint configured (tactical.elastic.endpoints)");
        }
        String active = props.elastic().active();
        if (active != null) {
            for (TacticalProperties.Endpoint endpoint : endpoints) {
                if (active.equalsIgnoreCase(endpoint.label())) {
                    return endpoint;
                }
            }
        }
        return endpoints.get(0);
    }

    /** Root URL of the active cluster, without a trailing slash. */
    public String baseUrl() {
        return activeEndpoint().url().replaceAll("/+$", "");
    }

    /** Sends any request and returns status plus parsed body, without judging the status. */
    public EsResponse raw(String method, String path, Object body) {
        String payload = null;
        if (body != null) {
            payload = body instanceof String text ? text : write(body);
        }
        return exec(method, path, "application/json", payload);
    }

    /** Posts a newline-delimited payload, which is what the bulk endpoint expects. */
    public EsResponse ndjson(String path, String payload) {
        return exec("POST", path, "application/x-ndjson", payload);
    }

    /** The one place a request is built, authenticated, sent and parsed. */
    private EsResponse exec(String method, String path, String contentType, String payload) {
        TacticalProperties.Endpoint endpoint = activeEndpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .header("Accept", "application/json");
        if (payload != null) {
            builder.header("Content-Type", contentType);
        }
        String username = endpoint.username();
        if (username != null && !username.isBlank()) {
            String password = endpoint.password() == null ? "" : endpoint.password();
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = response.body() == null || response.body().isBlank()
                    ? null
                    : mapper.readTree(response.body());
            return new EsResponse(response.statusCode(), parsed);
        } catch (IOException error) {
            throw new ApiException(502, "Elasticsearch unreachable at " + baseUrl() + ": " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "Elasticsearch request interrupted");
        }
    }

    /** GET that throws when the cluster answers with an error. */
    public JsonNode get(String path) {
        return require(raw("GET", path, null), "GET " + path);
    }

    /** POST that throws when the cluster answers with an error. */
    public JsonNode post(String path, Object body) {
        return require(raw("POST", path, body), "POST " + path);
    }

    /** PUT that throws when the cluster answers with an error. */
    public JsonNode put(String path, Object body) {
        return require(raw("PUT", path, body), "PUT " + path);
    }

    /** DELETE that throws when the cluster answers with an error. */
    public JsonNode delete(String path) {
        return require(raw("DELETE", path, null), "DELETE " + path);
    }

    /** Turns a non-2xx answer into an ApiException carrying the cluster's own reason. */
    public JsonNode require(EsResponse response, String label) {
        if (!response.ok()) {
            throw new ApiException(response.status(), label + " failed: " + reason(response.body()));
        }
        return response.body();
    }

    /** The cluster puts the real cause in the body; a bare status code hides it. */
    public static String reason(JsonNode body) {
        if (body == null) {
            return "no response body";
        }
        JsonNode error = body.path("error");
        String rootCause = error.path("root_cause").path(0).path("reason").asText("");
        if (!rootCause.isBlank()) {
            return rootCause;
        }
        String reason = error.path("reason").asText("");
        if (!reason.isBlank()) {
            return reason;
        }
        String type = error.path("type").asText("");
        return type.isBlank() ? body.toString() : type;
    }

    /** Percent-encodes one path segment, so an index name can never break the URL. */
    public static String enc(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
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
