package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tactical.core.api.ApiException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * A core's contents as a file. NDJSON keeps every stored field and is what a re-import wants;
 * CSV carries the columns a spreadsheet can read. The embedding vector is excluded from both,
 * because it is regenerable from the text and would dominate the file.
 */
@Service
public class ExportService {

    /** Hard ceiling on one export, so a huge core cannot exhaust the heap in a single response. */
    private static final int MAX_DOCS = 10000;

    private static final List<String> COLUMNS =
            List.of("title", "url", "tags", "query", "snippet", "scraped_at");

    private final EsClient es;
    private final IndexService index;
    private final ObjectMapper mapper;

    /** Wires the cluster, the core naming and the JSON mapper. */
    public ExportService(EsClient es, IndexService index, ObjectMapper mapper) {
        this.es = es;
        this.index = index;
        this.mapper = mapper;
    }

    /** One JSON object per line: id, index and every stored field except the vector. */
    public String ndjson(String name, int requested) {
        JsonNode hits = fetch(name, requested);
        StringBuilder out = new StringBuilder();
        for (JsonNode hit : hits) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("_id", hit.path("_id").asText());
            line.put("_index", hit.path("_index").asText());
            Iterator<Map.Entry<String, JsonNode>> fields = hit.path("_source").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                line.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
            }
            try {
                out.append(mapper.writeValueAsString(line)).append('\n');
            } catch (Exception error) {
                throw new ApiException(500, "Could not serialise a document: " + error.getMessage());
            }
        }
        return out.toString();
    }

    /** The same documents as CSV, tags flattened into one quoted cell. */
    public String csv(String name, int requested) {
        JsonNode hits = fetch(name, requested);
        StringBuilder out = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            List<String> row = new ArrayList<>();
            for (String column : COLUMNS) {
                JsonNode value = source.path(column);
                row.add(quote(value.isArray() ? joinArray(value) : value.asText("")));
            }
            out.append(String.join(",", row)).append('\n');
        }
        return out.toString();
    }

    /** Reads up to the limit from one core in stored order, the vector left out of the body. */
    private JsonNode fetch(String name, int requested) {
        String indexName = index.resolve(name);
        int limit = Math.min(Math.max(requested, 1), MAX_DOCS);
        JsonNode json = es.post("/" + EsClient.enc(indexName) + "/_search", Map.of(
                "size", limit,
                "sort", List.of("_doc"),
                "_source", Map.of("excludes", List.of("embedding")),
                "query", Map.of("match_all", Map.of())));
        return json.path("hits").path("hits");
    }

    private String joinArray(JsonNode array) {
        List<String> parts = new ArrayList<>();
        for (JsonNode entry : array) {
            parts.add(entry.asText(""));
        }
        return String.join("; ", parts);
    }

    /** RFC 4180 quoting: embedded quotes doubled, the cell always wrapped. */
    private String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
