package com.tactical.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.ChatRequest;
import com.tactical.core.api.Dtos.Message;
import com.tactical.core.api.Dtos.Persona;
import com.tactical.core.config.TacticalProperties;
import com.tactical.core.service.OllamaClient;
import com.tactical.core.service.PersonaService;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final OllamaClient ollama;
    private final TacticalProperties props;
    private final ObjectMapper mapper;
    private final PersonaService personas;

    /** Wires the model client, the default model, the JSON mapper and the persona registry. */
    public ChatController(OllamaClient ollama, TacticalProperties props, ObjectMapper mapper,
                          PersonaService personas) {
        this.ollama = ollama;
        this.props = props;
        this.mapper = mapper;
        this.personas = personas;
    }

    @PostMapping
    /** One complete answer, with the model used, the persona that framed it and the elapsed time. */
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        Persona persona = persona(request);
        String model = model(request);
        long started = System.currentTimeMillis();
        String answer = ollama.chat(messages(request, persona), model, false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", model);
        response.put("persona", persona == null ? null : persona.id());
        response.put("answer", answer);
        response.put("timeMs", System.currentTimeMillis() - started);
        return response;
    }

    /**
     * Real streaming: every delta is written and flushed to the client as its own SSE frame
     * the moment the model produces it, so a browser can render the answer as it is written.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody ChatRequest request) {
        Persona persona = persona(request);
        String model = model(request);
        List<Map<String, String>> messages = messages(request, persona);
        StreamingResponseBody body = outputStream -> {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            try {
                OllamaClient.ChatStream stream = ollama.streamChat(messages, model, delta -> {
                    writer.print(frame(Map.of("delta", delta)));
                    writer.flush();
                });
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("done", true);
                done.put("tokens", stream.tokens());
                if (persona != null) {
                    done.put("persona", persona.id());
                }
                writer.print(frame(done));
                writer.flush();
            } catch (RuntimeException error) {
                writer.print(frame(Map.of("error", String.valueOf(error.getMessage()))));
                writer.flush();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(body);
    }

    /** Wraps one payload as a single SSE data frame. */
    private String frame(Map<String, Object> payload) {
        try {
            return "data: " + mapper.writeValueAsString(payload) + "\n\n";
        } catch (IOException error) {
            return "data: {\"error\":\"serialisation failed\"}\n\n";
        }
    }

    /** The model named in the request, or the configured default. */
    private String model(ChatRequest request) {
        String model = request.model();
        return model == null || model.isBlank() ? props.ollama().chatModel() : model;
    }

    /**
     * The persona this turn runs as: the one the body named, otherwise the configured default
     * when one is imported, otherwise none. A named persona that was never imported is refused
     * outright rather than silently answered in the default voice.
     */
    private Persona persona(ChatRequest request) {
        if (request.persona() != null && !request.persona().isBlank()) {
            return personas.require(request.persona());
        }
        return personas.defaultPersona();
    }

    /** Normalises prompt-or-messages into the OpenAI message list, refusing an empty turn. */
    private List<Map<String, String>> messages(ChatRequest request, Persona persona) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.messages() != null) {
            for (Message message : request.messages()) {
                if (message == null || message.content() == null || message.content().isBlank()) {
                    continue;
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("role", message.role() == null || message.role().isBlank() ? "user" : message.role());
                entry.put("content", message.content());
                messages.add(entry);
            }
        }
        if (messages.isEmpty() && request.prompt() != null && !request.prompt().isBlank()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", request.prompt());
            messages.add(entry);
        }
        if (messages.isEmpty()) {
            throw new ApiException(400, "Send a prompt or a messages array");
        }
        // The persona directive leads the list, so it frames every turn that follows it
        if (persona != null) {
            Map<String, String> directive = new LinkedHashMap<>();
            directive.put("role", "system");
            directive.put("content", persona.systemPrompt());
            messages.add(0, directive);
        }
        return messages;
    }
}
