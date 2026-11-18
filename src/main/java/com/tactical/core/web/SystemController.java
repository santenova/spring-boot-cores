package com.tactical.core.web;

import com.tactical.core.service.EsClient;
import com.tactical.core.service.IndexService;
import com.tactical.core.service.OllamaClient;
import com.tactical.core.service.PersonaService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SystemController {

    private final OllamaClient ollama;
    private final EsClient es;
    private final IndexService index;
    private final PersonaService personas;

    /** Wires both hosts, the core metadata and the persona registry for the health probe. */
    public SystemController(OllamaClient ollama, EsClient es, IndexService index, PersonaService personas) {
        this.ollama = ollama;
        this.es = es;
        this.index = index;
        this.personas = personas;
    }

    @GetMapping("/models")
    /** Model ids the configured host serves. */
    public List<String> models() {
        return ollama.listModels();
    }

    /** Both dependencies are probed separately, so one failure still reports the other. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        // Personas need neither host, so they are reported before either probe runs
        body.put("personas", personas.list().size());
        try {
            body.put("ollama", true);
            body.put("models", ollama.listModels().size());
        } catch (RuntimeException error) {
            body.put("ollama", false);
            body.put("ollamaError", error.getMessage());
        }
        try {
            body.put("elasticsearch", es.raw("GET", "/", null).status() == 200);
            body.put("cores", index.coreIndices().size());
        } catch (RuntimeException error) {
            body.put("elasticsearch", false);
            body.put("elasticError", error.getMessage());
        }
        return body;
    }
}
