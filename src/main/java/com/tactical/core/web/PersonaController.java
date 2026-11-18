package com.tactical.core.web;

import com.tactical.core.api.Dtos.Persona;
import com.tactical.core.api.Dtos.PersonaImportResult;
import com.tactical.core.service.PersonaService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/personas")
public class PersonaController {

    private final PersonaService personas;

    /** Wires the persona registry that chat also reads. */
    public PersonaController(PersonaService personas) {
        this.personas = personas;
    }

    @GetMapping
    /** Every imported persona, each with the directive chat will send. */
    public List<Persona> list() {
        return personas.list();
    }

    @GetMapping("/{id}")
    /** One persona by id or display name; an unknown key is a 404. */
    public Persona get(@PathVariable String id) {
        return personas.require(id);
    }

    @PostMapping
    /** Adds a persona defined inline in the body, without a file. */
    public Persona create(@RequestBody Persona request) {
        return personas.create(request);
    }

    /**
     * Imports personas from a JSON file. With no body the shipped sample is read; with
     * {"path": "..."} any classpath: or filesystem path is. Each entry may be a persona on its
     * own or an Elasticsearch hit carrying it under _source, so a prompt-hub export drops in
     * unchanged.
     */
    @PostMapping("/import")
    public PersonaImportResult importFile(@RequestBody(required = false) Map<String, String> request) {
        return personas.importFile(request == null ? null : request.get("path"));
    }

    @DeleteMapping("/{id}")
    /** Removes one persona and confirms which id went. */
    public Map<String, Object> delete(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", personas.delete(id));
        return body;
    }
}
