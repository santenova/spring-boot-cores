package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.Persona;
import com.tactical.core.api.Dtos.PersonaImportResult;
import com.tactical.core.config.TacticalProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * The persona registry behind chat. A persona is a named directive that leads the message
 * list, so one chat body can switch the whole voice of the answer. The registry lives in
 * memory and is refilled at startup from the configured file, so nothing has to be imported
 * before the first turn; the endpoints exist for other files and for re-imports.
 */
@Service
public class PersonaService {

    /** Shipped with the project, so the import can be exercised before a real export exists. */
    public static final String SAMPLE_FILE = "classpath:sample-data/personas.json";

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);

    private final ObjectMapper mapper;
    private final TacticalProperties props;
    private final Map<String, Persona> personas = new LinkedHashMap<>();

    /** Wires the JSON mapper and the persona settings the startup import reads. */
    public PersonaService(ObjectMapper mapper, TacticalProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Loads the configured file at startup, so a persona can be named in the very first chat
     * turn. A missing or malformed file is logged rather than thrown: the service still has to
     * start when the sample was not packaged, and the import endpoint can add personas later.
     */
    @PostConstruct
    void loadAtStartup() {
        if (!props.personas().autoImport()) {
            log.info("Persona auto-import is off - POST /api/personas/import to load a file");
            return;
        }
        String source = props.personas().sampleFile();
        try {
            PersonaImportResult result = importFile(source);
            log.info("Imported {} persona(s) from {} ({} skipped)",
                    result.created(), result.source(), result.skipped());
        } catch (RuntimeException error) {
            log.warn("No personas loaded from {}: {}", source, error.getMessage());
        }
    }

    /** Every imported persona, in the order they arrived. */
    public synchronized List<Persona> list() {
        return new ArrayList<>(personas.values());
    }

    /** A persona by id, or by its display name when the caller used the human label. */
    public synchronized Persona find(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim();
        Persona byId = personas.get(wanted);
        if (byId != null) {
            return byId;
        }
        for (Persona persona : personas.values()) {
            if (persona.name().equalsIgnoreCase(wanted)) {
                return persona;
            }
        }
        return null;
    }

    /** The persona a turn asked for; an unknown key is a 404 that names the import endpoint. */
    public Persona require(String key) {
        Persona persona = find(key);
        if (persona == null) {
            throw new ApiException(404, "Unknown persona '" + key + "' - import one first: POST /api/personas/import");
        }
        return persona;
    }

    /**
     * The persona a chat turn falls back to when its body names none: the configured default,
     * or null when that is empty or was never imported.
     */
    public synchronized Persona defaultPersona() {
        String configured = props.personas().defaultPersona();
        return configured == null || configured.isBlank() ? null : find(configured);
    }

    /** Adds one persona defined inline in a request body. */
    public synchronized Persona create(Persona request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(400, "A persona needs a name");
        }
        String id = request.id() == null || request.id().isBlank() ? request.name() : request.id();
        Persona persona = normalise(id, request.name(), request.description(), request.instructions(),
                request.tone(), request.category(), request.icon(), request.expertiseAreas(),
                request.tags(), "inline");
        personas.put(persona.id(), persona);
        return persona;
    }

    /** Deletes one persona by id or name and reports which id went. */
    public synchronized String delete(String key) {
        Persona persona = require(key);
        personas.remove(persona.id());
        return persona.id();
    }

    /**
     * Imports every persona in a JSON file: the shipped sample when no path is given, otherwise
     * a classpath: or filesystem path. A plain array of personas, a single persona and an
     * Elasticsearch export (each persona wrapped in _source) all read the same way. Importing
     * the same file twice updates the entries in place instead of duplicating them.
     */
    public synchronized PersonaImportResult importFile(String source) {
        String location = source == null || source.isBlank() ? SAMPLE_FILE : source.trim();
        JsonNode root = read(location);
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else {
            nodes.add(root);
        }
        List<String> lines = new ArrayList<>();
        int created = 0;
        int skipped = 0;
        for (JsonNode node : nodes) {
            JsonNode body = node.has("_source") ? node.get("_source") : node;
            String name = value(body, "name");
            if (name.isEmpty()) {
                skipped++;
                lines.add("skipped an entry with no name");
                continue;
            }
            String id = value(body, "id");
            Persona persona = normalise(id.isEmpty() ? name : id, name, value(body, "description"),
                    value(body, "instructions"), value(body, "tone"), value(body, "category"),
                    value(body, "icon"), values(body, "expertise_areas"), values(body, "tags"), location);
            personas.put(persona.id(), persona);
            created++;
            lines.add(persona.id() + " (" + persona.name() + ")");
        }
        lines.add(0, "source: " + location);
        return new PersonaImportResult(location, nodes.size(), created, skipped, lines);
    }

    /** Builds the stored persona: a stable id, immutable list fields, and the directive. */
    private static Persona normalise(String id, String name, String description, String instructions,
                                     String tone, String category, String icon, List<String> expertise,
                                     List<String> tags, String source) {
        List<String> areas = expertise == null ? List.of() : List.copyOf(expertise);
        List<String> labels = tags == null ? List.of() : List.copyOf(tags);
        String label = name.trim();
        return new Persona(slug(id), label, text(description), text(instructions), text(tone),
                text(category), text(icon), areas, labels,
                directive(label, description, instructions, tone, areas), source);
    }

    /**
     * The system message a persona contributes. It leads the message list, so every later turn
     * is read in its voice: who it is, what it was told to do, how it should sound, and what it
     * knows most about.
     */
    private static String directive(String name, String description, String instructions,
                                    String tone, List<String> expertise) {
        StringBuilder prompt = new StringBuilder("You are ").append(name).append('.');
        if (!text(description).isEmpty()) {
            prompt.append(' ').append(text(description));
        }
        if (!text(instructions).isEmpty()) {
            prompt.append("\n\nINSTRUCTIONS: ").append(text(instructions));
        }
        if (!text(tone).isEmpty()) {
            prompt.append("\n\nTONE: ").append(text(tone));
        }
        if (!expertise.isEmpty()) {
            prompt.append("\n\nEXPERTISE: ").append(String.join(", ", expertise));
        }
        return prompt.toString();
    }

    /** Reads the file the import was pointed at, refusing a missing one with a readable 404. */
    private JsonNode read(String location) {
        try (InputStream stream = open(location)) {
            return mapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new ApiException(400, "Could not read personas from " + location + ": " + error.getMessage());
        }
    }

    /** Opens a classpath: resource or a filesystem path, whichever the caller named. */
    private InputStream open(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            ClassPathResource resource = new ClassPathResource(location.substring("classpath:".length()));
            if (!resource.exists()) {
                throw new ApiException(404, "No such file on the classpath: " + location);
            }
            return resource.getInputStream();
        }
        Path path = Path.of(location);
        if (!Files.exists(path)) {
            throw new ApiException(404, "No such file: " + location);
        }
        return Files.newInputStream(path);
    }

    /** One text field of a persona node, trimmed, empty when absent or null. */
    private static String value(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    /** One string-array field of a persona node, empty entries dropped. */
    private static List<String> values(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode array = node.get(field);
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                String entry = item.asText("").trim();
                if (!entry.isEmpty()) {
                    out.add(entry);
                }
            });
        }
        return out;
    }

    /** A URL-safe id from a name, so a persona can be named in a path or a chat body. */
    private static String slug(String raw) {
        String slug = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "persona" : slug;
    }

    /** Null-safe trimmed text, so a missing field never reaches the directive as "null". */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
