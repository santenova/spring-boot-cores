package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.CoreInfo;
import com.tactical.core.config.TacticalProperties;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Core lifecycle: naming, mapping, the vector field every core needs, and deletion.
 */
@Service
public class IndexService {

    public record VectorField(String type, Integer dims) {
    }

    public record Variant(String label, Map<String, Object> mapping) {
    }

    public record EnsureResult(boolean ok, String mode, String error, Integer dims) {
    }

    private final EsClient es;
    private final EmbeddingService embeddings;
    private final TacticalProperties props;

    /** Wires the cluster, the embedding model and the configured index prefix. */
    public IndexService(EsClient es, EmbeddingService embeddings, TacticalProperties props) {
        this.es = es;
        this.embeddings = embeddings;
        this.props = props;
    }

    /** The prefix every knowledge core index carries. */
    public String prefix() {
        return props.elastic().indexPrefix();
    }

    /** Turns a core name into a safe, lowercase index name. */
    public String coreIndex(String name) {
        String safe = (name == null || name.isBlank() ? "default" : name)
                .toLowerCase()
                .replaceAll("[^a-z0-9_]", "_");
        return prefix() + safe;
    }

    /** Accepts either a short core name or a full index name. */
    public String resolve(String nameOrIndex) {
        if (nameOrIndex == null || nameOrIndex.isBlank()) {
            throw new ApiException(400, "A core name is required");
        }
        return nameOrIndex.startsWith(prefix()) ? nameOrIndex : coreIndex(nameOrIndex);
    }

    /** Widest cluster support last: quantized HNSW, plain HNSW, indexed vector, bare vector. */
    public List<Variant> variants(int dims) {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("int8_hnsw", denseVector(dims, "int8_hnsw")));
        variants.add(new Variant("hnsw", denseVector(dims, "hnsw")));
        Map<String, Object> indexed = new LinkedHashMap<>();
        indexed.put("type", "dense_vector");
        indexed.put("dims", dims);
        indexed.put("index", true);
        indexed.put("similarity", "cosine");
        variants.add(new Variant("dense_vector", indexed));
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("type", "dense_vector");
        bare.put("dims", dims);
        variants.add(new Variant("dense_vector_plain", bare));
        return variants;
    }

    /** A cosine dense_vector mapping using the given HNSW flavour. */
    private Map<String, Object> denseVector(int dims, String hnswType) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("type", "dense_vector");
        mapping.put("dims", dims);
        mapping.put("index", true);
        mapping.put("similarity", "cosine");
        mapping.put("index_options", Map.of("type", hnswType, "m", 16, "ef_construction", 100));
        return mapping;
    }

    /** The text fields every core stores, independent of embeddings. */
    private Map<String, Object> baseProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "keyword"));
        properties.put("tags", Map.of("type", "keyword"));
        properties.put("title", Map.of("type", "text"));
        properties.put("url", Map.of("type", "keyword"));
        properties.put("snippet", Map.of("type", "text"));
        properties.put("scraped_at", Map.of("type", "date"));
        return properties;
    }

    /** The stored vector field, or null when the core has none or it is not a dense_vector. */
    public VectorField readEmbeddingField(String index) {
        JsonNode json;
        try {
            json = es.get("/" + EsClient.enc(index) + "/_mapping");
        } catch (RuntimeException error) {
            return null;
        }
        if (json == null) {
            return null;
        }
        JsonNode definition = json.path(index);
        if (definition.isMissingNode() || definition.isNull()) {
            Iterator<JsonNode> values = json.elements();
            definition = values.hasNext() ? values.next() : null;
        }
        if (definition == null) {
            return null;
        }
        JsonNode field = definition.path("mappings").path("properties").path("embedding");
        if (!"dense_vector".equals(field.path("type").asText(""))) {
            return null;
        }
        Integer width = field.path("dims").isInt() ? Integer.valueOf(field.path("dims").asInt()) : null;
        return new VectorField("dense_vector", width);
    }

    /** Adds the vector field, trying each mapping form until the cluster accepts one. */
    public EnsureResult ensureEmbeddingField(String index, int dims) {
        VectorField existing = readEmbeddingField(index);
        if (existing != null) {
            return new EnsureResult(true, "existing", "", existing.dims());
        }
        List<String> errors = new ArrayList<>();
        for (Variant variant : variants(dims)) {
            Map<String, Object> field = Map.of("embedding", variant.mapping());
            EsClient.EsResponse response = es.raw("PUT", "/" + EsClient.enc(index) + "/_mapping",
                    Map.of("properties", field));
            if (response.ok()) {
                VectorField stored = readEmbeddingField(index);
                if (stored != null) {
                    return new EnsureResult(true, variant.label(), "", stored.dims());
                }
                errors.add(variant.label() + ": field absent after the update");
                continue;
            }
            errors.add(variant.label() + ": HTTP " + response.status() + " - " + EsClient.reason(response.body()));
        }
        return new EnsureResult(false, "", String.join(" | ", errors), null);
    }

    /**
     * Creates the core, with the vector field in the very first request when embeddings are
     * on: a core that receives a vector before its mapping exists gets the name auto-mapped
     * as a float array, and that can never become a dense_vector again.
     */
    public String ensureIndex(String index, boolean withVectors) {
        if (es.raw("HEAD", "/" + EsClient.enc(index), null).status() == 200) {
            return "exists";
        }
        if (withVectors) {
            for (Variant variant : variants(embeddings.dims())) {
                Map<String, Object> properties = baseProperties();
                properties.put("embedding", variant.mapping());
                EsClient.EsResponse response = es.raw("PUT", "/" + EsClient.enc(index),
                        Map.of("mappings", Map.of("properties", properties)));
                if (response.ok()) {
                    return "created:" + variant.label();
                }
            }
        }
        EsClient.EsResponse response = es.raw("PUT", "/" + EsClient.enc(index),
                Map.of("mappings", Map.of("properties", baseProperties())));
        if (!response.ok()) {
            throw new ApiException(response.status(),
                    "Could not create " + index + ": " + EsClient.reason(response.body()));
        }
        return "created";
    }

    /** Deletes a core index outright; there is no soft delete. */
    public void deleteCore(String index) {
        EsClient.EsResponse response = es.raw("DELETE", "/" + EsClient.enc(index), null);
        if (!response.ok()) {
            throw new ApiException(response.status(),
                    "Could not delete " + index + ": " + EsClient.reason(response.body()));
        }
    }

    /** Every core with its document count and vector readiness, largest first. */
    public List<CoreInfo> listCores() {
        List<CoreInfo> cores = new ArrayList<>();
        JsonNode rows = es.get("/_cat/indices/" + prefix() + "*?format=json&h=index,docs.count");
        if (rows == null || !rows.isArray()) {
            return cores;
        }
        for (JsonNode row : rows) {
            String index = row.path("index").asText("");
            if (index.isBlank()) {
                continue;
            }
            VectorField field = readEmbeddingField(index);
            cores.add(new CoreInfo(index, index.replace(prefix(), ""),
                    parseLong(row.path("docs.count").asText("0")), field != null,
                    field == null ? null : field.dims()));
        }
        cores.sort((a, b) -> Long.compare(b.documents(), a.documents()));
        return cores;
    }

    /** The names of all core indices, used as the default search scope. */
    public List<String> coreIndices() {
        List<String> names = new ArrayList<>();
        JsonNode rows = es.get("/_cat/indices/" + prefix() + "*?format=json&h=index");
        if (rows == null || !rows.isArray()) {
            return names;
        }
        for (JsonNode row : rows) {
            String index = row.path("index").asText("");
            if (!index.isBlank()) {
                names.add(index);
            }
        }
        return names;
    }

    /** The indices the cores were built from: everything that is not itself a core. */
    public List<String> sourceIndices() {
        List<String> names = new ArrayList<>();
        JsonNode rows = es.get("/_cat/indices?format=json&h=index");
        if (rows == null || !rows.isArray()) {
            return names;
        }
        for (JsonNode row : rows) {
            String index = row.path("index").asText("");
            if (index.isBlank() || index.startsWith(".") || index.startsWith(prefix())
                    || index.contains("_core_")) {
                continue;
            }
            names.add(index);
        }
        return names;
    }

    /** Parses a _cat count, which may arrive with thousands separators. */
    public static long parseLong(String value) {
        try {
            return Long.parseLong(value.replace(",", "").trim());
        } catch (RuntimeException error) {
            return 0;
        }
    }
}
