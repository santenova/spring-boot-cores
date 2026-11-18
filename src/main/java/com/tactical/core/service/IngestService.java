package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.IngestRequest;
import com.tactical.core.api.Dtos.IngestResult;
import com.tactical.core.config.TacticalProperties;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Core ingestion: expand a topic into terms, search every source index with a wide net, tag
 * each document, embed it and bulk index it into the core.
 */
@Service
public class IngestService {

    private static final class Doc {
        String key;
        String title;
        String url;
        String snippet;
        final Set<String> tags = new LinkedHashSet<>();
        double[] vector;
    }

    private final EsClient es;
    private final OllamaClient ollama;
    private final IndexService index;
    private final EmbeddingService embeddings;
    private final TacticalProperties props;
    private final ObjectMapper mapper;

    /** Wires both hosts, the core metadata, the embedding model and the JSON mapper. */
    public IngestService(EsClient es, OllamaClient ollama, IndexService index, EmbeddingService embeddings,
                         TacticalProperties props, ObjectMapper mapper) {
        this.es = es;
        this.ollama = ollama;
        this.index = index;
        this.embeddings = embeddings;
        this.props = props;
        this.mapper = mapper;
    }

    /** Expands each topic, harvests matches, embeds them and bulk indexes the core. */
    public IngestResult run(IngestRequest request) {
        List<String> queries = request.queries() == null ? List.of() : request.queries();
        if (queries.isEmpty()) {
            throw new ApiException(400, "At least one query is required");
        }
        boolean expand = request.expandQueries() == null || request.expandQueries();
        boolean withVectors = request.embed() == null || request.embed();
        int wideNet = request.resultLimit() == null
                ? props.search().wideNetLimit()
                : Math.min(Math.max(request.resultLimit(), 1), 10000);
        String indexName = index.coreIndex(request.coreName());
        List<String> log = new ArrayList<>();

        List<String> sourceIndices = index.sourceIndices();
        if (sourceIndices.isEmpty()) {
            throw new ApiException(404, "No source indices to ingest from - add documents to Elasticsearch first");
        }
        log.add("Source indices: " + sourceIndices.size());
        log.add("Target core: " + indexName + " (" + index.ensureIndex(indexName, withVectors) + ")");
        if (withVectors) {
            IndexService.EnsureResult ensured = index.ensureEmbeddingField(indexName, embeddings.dims());
            log.add(ensured.ok()
                    ? "Vector field ready (" + embeddings.dims() + "d, " + ensured.mode() + ")"
                    : "Vector field NOT added: " + ensured.error());
        }

        Map<String, Doc> collected = new LinkedHashMap<>();
        for (String raw : queries) {
            String query = raw == null ? "" : raw.trim();
            if (query.isEmpty()) {
                continue;
            }
            List<String> terms = expand ? expandQuery(query, log) : List.of(query);
            List<Doc> found = wideNet(sourceIndices, terms, wideNet);
            int added = 0;
            for (Doc doc : found) {
                Doc existing = collected.get(doc.key);
                if (existing != null) {
                    existing.tags.add(query);
                    continue;
                }
                doc.tags.add(query);
                doc.tags.addAll(Keywords.top(doc.snippet, 3));
                collected.put(doc.key, doc);
                added++;
            }
            log.add("[" + query + "] " + found.size() + " matched, " + added + " new");
        }

        List<Doc> docs = new ArrayList<>(collected.values());
        int vectors = 0;
        if (withVectors) {
            for (Doc doc : docs) {
                try {
                    doc.vector = embeddings.embed(embeddingText(doc));
                    vectors++;
                } catch (RuntimeException error) {
                    log.add("  vector failed for " + doc.title + ": " + error.getMessage());
                }
            }
        }
        int[] counts = bulk(indexName, docs);
        log.add("Indexed " + counts[0] + ", rejected " + counts[1] + ", vectors " + vectors);
        return new IngestResult(indexName, log, counts[0], counts[1], vectors);
    }

    /** Asks the model for synonyms and sub-fields; a failure falls back to the plain query. */
    private List<String> expandQuery(String query, List<String> log) {
        String prompt = "You are a search query expansion expert. Given the topic \"" + query + "\", return a JSON object "
                + "with a terms array of 10-14 search terms in two groups: 1) synonyms and related concepts, "
                + "2) named branches, varieties or sub-fields of this exact topic. Concrete established terms only.\n"
                + "Format: {\"terms\": [\"term1\", \"term2\"]}";
        try {
            String raw = ollama.chat(List.of(Map.of("role", "user", "content", prompt)),
                    props.ollama().chatModel(), true);
            Object plain = mapper.convertValue(mapper.readTree(raw), Object.class);
            List<String> cleaned = new ArrayList<>();
            for (String term : Keywords.flatten(plain, new ArrayList<>())) {
                String value = Keywords.cleanTerm(term);
                if (!value.isBlank()) {
                    cleaned.add(value);
                }
            }
            List<String> terms = Keywords.dedupe(cleaned);
            if (terms.size() > 14) {
                terms = new ArrayList<>(terms.subList(0, 14));
            }
            if (terms.isEmpty()) {
                return List.of(query);
            }
            log.add("  expanded into " + terms.size() + " terms");
            List<String> all = new ArrayList<>();
            all.add(query);
            all.addAll(terms);
            return all;
        } catch (RuntimeException | IOException error) {
            log.add("  expansion failed (" + error.getMessage() + ") - using the plain query");
            return List.of(query);
        }
    }

    /** Searches every source index at once and normalises the hits into documents. */
    private List<Doc> wideNet(List<String> sourceIndices, List<String> terms, int limit) {
        List<String> cleaned = new ArrayList<>();
        for (String term : terms) {
            String value = Keywords.cleanTerm(term);
            if (!value.isBlank()) {
                cleaned.add(value);
            }
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        Map<String, Object> queryString = new LinkedHashMap<>();
        queryString.put("query", String.join(" OR ", cleaned));
        queryString.put("default_operator", "OR");
        queryString.put("fuzziness", "AUTO");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", limit);
        body.put("query", Map.of("query_string", queryString));
        body.put("_source", true);

        JsonNode json = es.post("/" + String.join(",", sourceIndices) + "/_search", body);
        List<Doc> docs = new ArrayList<>();
        for (JsonNode hit : json.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            String id = hit.path("_id").asText("");
            String indexName = hit.path("_index").asText("");
            String title = Keywords.firstText(source, "title", "name", "subject", "headline");
            if (title.isBlank()) {
                title = id;
            }
            String url = Keywords.firstText(source, "url", "link", "source", "uri");
            if (url.isBlank()) {
                url = "es://" + indexName + "/" + id;
            }
            String snippet = Keywords.firstText(source, "snippet", "content", "body", "text",
                    "description", "summary");
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300);
            }
            Doc doc = new Doc();
            doc.key = url;
            doc.title = title.length() > 200 ? title.substring(0, 200) : title;
            doc.url = url;
            doc.snippet = snippet;
            docs.add(doc);
        }
        return docs;
    }

    /** Title, snippet and tags together, so a document is reachable by every topic it matched. */
    private String embeddingText(Doc doc) {
        return doc.title + " - " + doc.snippet + " - " + String.join(", ", doc.tags);
    }

    /** Bulk indexes the documents and returns the accepted and rejected counts. */
    private int[] bulk(String indexName, List<Doc> docs) {
        if (docs.isEmpty()) {
            return new int[]{0, 0};
        }
        StringBuilder payload = new StringBuilder();
        for (Doc doc : docs) {
            Map<String, Object> action = Map.of("index",
                    Map.of("_index", indexName, "_id", docId(doc)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", doc.tags.isEmpty() ? "" : doc.tags.iterator().next());
            body.put("tags", new ArrayList<>(doc.tags));
            body.put("title", doc.title);
            body.put("url", doc.url);
            body.put("snippet", doc.snippet);
            body.put("scraped_at", Instant.now().toString());
            if (doc.vector != null) {
                body.put("embedding", doc.vector);
            }
            try {
                payload.append(mapper.writeValueAsString(action)).append('\n')
                        .append(mapper.writeValueAsString(body)).append('\n');
            } catch (IOException error) {
                throw new ApiException(500, "Could not serialise a bulk request");
            }
        }
        JsonNode json = es.require(es.ndjson("/_bulk", payload.toString()), "Bulk index");
        int ok = 0;
        int failed = 0;
        for (JsonNode item : json.path("items")) {
            int status = item.path("index").path("status").asInt(0);
            if (status >= 200 && status < 300) {
                ok++;
            } else {
                failed++;
            }
        }
        return new int[]{ok, failed};
    }

    /** Stable id from the document address, so a document hit by several queries is updated. */
    private String docId(Doc doc) {
        int hash = 5381;
        for (int i = 0; i < doc.key.length(); i++) {
            hash = ((hash << 5) + hash) + doc.key.charAt(i);
        }
        return "doc_" + Integer.toUnsignedString(hash, 36);
    }
}
