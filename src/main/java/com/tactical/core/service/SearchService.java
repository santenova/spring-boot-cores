package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.SearchHit;
import com.tactical.core.api.Dtos.SearchRequest;
import com.tactical.core.api.Dtos.SearchResult;
import com.tactical.core.api.Dtos.TagCount;
import com.tactical.core.config.TacticalProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The three retrieval modes of the browser app, unchanged: BM25 over title and snippet, kNN
 * over the dense vector, and a hybrid that runs both and fuses the ranks.
 */
@Service
public class SearchService {

    private static final List<String> SOURCE_FIELDS =
            List.of("query", "tags", "title", "url", "snippet", "scraped_at");

    private final EsClient es;
    private final IndexService index;
    private final EmbeddingService embeddings;
    private final TacticalProperties props;

    /** Wires the cluster, the core metadata, the embedding model and the tuning values. */
    public SearchService(EsClient es, IndexService index, EmbeddingService embeddings, TacticalProperties props) {
        this.es = es;
        this.index = index;
        this.embeddings = embeddings;
        this.props = props;
    }

    /** Runs the requested mode: text, knn, or hybrid fused with reciprocal rank fusion. */
    public SearchResult search(SearchRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new ApiException(400, "A query is required");
        }
        int k = topK(request.k());
        List<String> cores = request.cores() == null || request.cores().isEmpty()
                ? index.coreIndices()
                : request.cores();
        if (cores.isEmpty()) {
            throw new ApiException(404, "No knowledge cores found - ingest one first");
        }
        String mode = request.mode() == null ? "text" : request.mode().toLowerCase();
        long started = System.currentTimeMillis();
        List<SearchHit> hits;
        long total;
        String note = "";

        if ("knn".equals(mode)) {
            double[] vector = embeddings.embed(query);
            List<String> searchable = vectorCores(cores, vector.length);
            if (searchable.size() < cores.size()) {
                note = "SEMANTIC SCOPE: " + searchable.size() + " OF " + cores.size()
                        + " CORES CARRY VECTORS - THE REST ARE SKIPPED";
            }
            JsonNode json = es.post("/" + String.join(",", searchable) + "/_search", knnBody(vector, k, null));
            hits = mapHits(json, "knn");
            total = totalOf(json, hits.size());
            if (hits.isEmpty()) {
                Long stored = countVectors(searchable);
                if (stored != null) {
                    note = stored == 0
                            ? "NO VECTORS STORED - THE CORES DECLARE THE FIELD BUT NO DOCUMENT HOLDS ONE; RE-INGEST WITH EMBEDDINGS ENABLED"
                            : "kNN FOUND NOTHING - " + stored + " DOCUMENTS IN SCOPE HOLD VECTORS";
                }
            }
        } else if ("hybrid".equals(mode)) {
            JsonNode textJson = es.post("/" + String.join(",", cores) + "/_search", textBody(query, k));
            double[] vector = null;
            List<String> searchable = List.of();
            List<String> withVectors = coresWithVectors(cores);
            if (withVectors.isEmpty()) {
                note = "HYBRID DEGRADED TO BM25 - NO CORE IN SCOPE HOLDS VECTORS";
            } else {
                vector = embeddings.embed(query);
                searchable = compatible(withVectors, vector.length);
                if (searchable.isEmpty()) {
                    note = "HYBRID DEGRADED TO BM25 - NO CORE MATCHES THE QUERY VECTOR WIDTH";
                    vector = null;
                } else if (searchable.size() < cores.size()) {
                    note = "SEMANTIC SCOPE: " + searchable.size() + " OF " + cores.size()
                            + " CORES CARRY VECTORS - THE REST ARE KEYWORD-ONLY";
                }
            }
            JsonNode knnJson = vector == null
                    ? null
                    : es.post("/" + String.join(",", searchable) + "/_search", knnBody(vector, k, null));

            Map<String, Double> scores = new LinkedHashMap<>();
            Map<String, SearchHit> byKey = new LinkedHashMap<>();
            int rrf = props.search().rrfK();
            collect(knnJson, rrf, scores, byKey);
            collect(textJson, rrf, scores, byKey);
            List<SearchHit> fused = new ArrayList<>();
            scores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(k)
                    .forEach(entry -> fused.add(byKey.get(entry.getKey()).withScore(entry.getValue())));
            hits = fused;
            total = fused.size();
        } else {
            JsonNode json = es.post("/" + String.join(",", cores) + "/_search", textBody(query, k));
            hits = mapHits(json, "text");
            total = totalOf(json, hits.size());
        }

        return new SearchResult(hits, total, System.currentTimeMillis() - started, note);
    }

    /**
     * Tag navigation: the tag text is embedded and searched with kNN, restricted to documents
     * carrying every tag on the path.
     */
    public SearchResult tagSearch(List<String> tags, Integer requestedK, List<String> requestedCores) {
        if (tags == null || tags.isEmpty()) {
            throw new ApiException(400, "At least one tag is required");
        }
        int k = topK(requestedK);
        List<String> cores = requestedCores == null || requestedCores.isEmpty()
                ? index.coreIndices()
                : requestedCores;
        if (cores.isEmpty()) {
            throw new ApiException(404, "No knowledge cores found - ingest one first");
        }
        long started = System.currentTimeMillis();
        String tag = tags.get(tags.size() - 1);
        double[] vector = embeddings.embed(tag);
        List<String> searchable = vectorCores(cores, vector.length);

        List<Map<String, Object>> must = new ArrayList<>();
        for (String step : tags) {
            must.add(Map.of("term", Map.of("tags", step)));
        }
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("bool", Map.of("must", must));

        JsonNode json = es.post("/" + String.join(",", searchable) + "/_search", knnBody(vector, k, filter));
        List<SearchHit> hits = mapHits(json, "knn");
        String note = hits.isEmpty()
                ? "NO DOCUMENT IN THIS TAG SCOPE HOLDS A VECTOR - RE-INGEST WITH EMBEDDINGS ENABLED"
                : "";
        return new SearchResult(hits, totalOf(json, hits.size()), System.currentTimeMillis() - started, note);
    }

    /** The most common tags in scope, counted by the cluster itself. */
    public List<TagCount> tagCounts(List<String> requestedCores) {
        List<String> cores = requestedCores == null || requestedCores.isEmpty()
                ? index.coreIndices()
                : requestedCores;
        List<TagCount> counts = new ArrayList<>();
        if (cores.isEmpty()) {
            return counts;
        }
        String target = String.join(",", cores);
        JsonNode json;
        try {
            json = es.post("/" + target + "/_search", termsAgg("tags"));
        } catch (ApiException error) {
            // A core whose tags were auto-mapped as text carries them under the keyword sub-field
            json = es.post("/" + target + "/_search", termsAgg("tags.keyword"));
        }
        for (JsonNode bucket : json.path("aggregations").path("tags").path("buckets")) {
            counts.add(new TagCount(bucket.path("key").asText(""), bucket.path("doc_count").asLong()));
        }
        return counts;
    }

    /** A size-zero search carrying only the tag terms aggregation. */
    private Map<String, Object> termsAgg(String field) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("field", field);
        terms.put("size", 60);
        Map<String, Object> aggs = new LinkedHashMap<>();
        aggs.put("tags", Map.of("terms", terms));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 0);
        body.put("aggs", aggs);
        return body;
    }

    /** Clamps the requested result count to the configured ceiling. */
    private int topK(Integer requested) {
        if (requested == null || requested < 1) {
            return props.search().defaultTopK();
        }
        return Math.min(requested, props.search().maxTopK());
    }

    /** The BM25 clause: the title outweighs the snippet, both matched fuzzily. */
    private Map<String, Object> textQuery(String query) {
        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("query", query);
        multi.put("fields", List.of("title^3", "snippet^1.5", "query"));
        multi.put("type", "best_fields");
        multi.put("fuzziness", "AUTO");
        return Map.of("multi_match", multi);
    }

    /** The full text-search request body. */
    private Map<String, Object> textBody(String query, int k) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", textQuery(query));
        body.put("_source", SOURCE_FIELDS);
        body.put("size", k);
        return body;
    }

    /** The full kNN request body, optionally restricted by a tag filter. */
    private Map<String, Object> knnBody(double[] vector, int k, Map<String, Object> filter) {
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "embedding");
        knn.put("query_vector", vector);
        knn.put("k", k);
        knn.put("num_candidates", k * props.search().numCandidatesFactor());
        if (filter != null) {
            knn.put("filter", filter);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("knn", knn);
        body.put("_source", SOURCE_FIELDS);
        body.put("size", k);
        return body;
    }

    /** The cores in scope whose mapping declares a dense_vector field. */
    private List<String> coresWithVectors(List<String> cores) {
        List<String> found = new ArrayList<>();
        for (String core : cores) {
            if (index.readEmbeddingField(core) != null) {
                found.add(core);
            }
        }
        return found;
    }

    /** The cores a query vector of this width can search, or a readable refusal. */
    private List<String> vectorCores(List<String> cores, int dims) {
        List<String> withVectors = coresWithVectors(cores);
        if (withVectors.isEmpty()) {
            throw new ApiException(409, "No core in scope has a vector field - re-ingest with embeddings enabled");
        }
        List<String> searchable = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (String core : withVectors) {
            Integer width = index.readEmbeddingField(core).dims();
            if (width == null || width == dims) {
                searchable.add(core);
            } else if (!widths.contains(width)) {
                widths.add(width);
            }
        }
        if (searchable.isEmpty()) {
            throw new ApiException(409, "Query vector is " + dims + "d but the cores store " + widths
                    + "d - re-ingest with the current embedding model");
        }
        return searchable;
    }

    /** Keeps only the cores whose stored width matches the query vector. */
    private List<String> compatible(List<String> cores, int dims) {
        List<String> searchable = new ArrayList<>();
        for (String core : cores) {
            Integer width = index.readEmbeddingField(core).dims();
            if (width == null || width == dims) {
                searchable.add(core);
            }
        }
        return searchable;
    }

    /** How many documents in scope actually carry a vector, or null if uncountable. */
    private Long countVectors(List<String> cores) {
        try {
            JsonNode json = es.post("/" + String.join(",", cores) + "/_count",
                    Map.of("query", Map.of("exists", Map.of("field", "embedding"))));
            return json.path("count").isNumber() ? Long.valueOf(json.path("count").asLong()) : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** Maps a search response into the API's hit shape. */
    private List<SearchHit> mapHits(JsonNode json, String mode) {
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hit : json.path("hits").path("hits")) {
            hits.add(toHit(hit, mode));
        }
        return hits;
    }

    /** One hit, normalised: title, url, snippet and tags whatever the source field names were. */
    private SearchHit toHit(JsonNode hit, String mode) {
        JsonNode source = hit.path("_source");
        String indexName = hit.path("_index").asText("");
        String id = hit.path("_id").asText("");
        String title = Keywords.firstText(source, "title", "name", "subject", "headline");
        if (title.isBlank()) {
            title = id;
        }
        String url = Keywords.firstText(source, "url", "link", "source", "uri");
        if (url.isBlank()) {
            url = "es://" + indexName + "/" + id;
        }
        String snippet = Keywords.firstText(source, "snippet", "content", "body", "text", "description", "summary");
        if (snippet.length() > 300) {
            snippet = snippet.substring(0, 300);
        }
        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = source.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                if (!tag.asText("").isBlank()) {
                    tags.add(tag.asText());
                }
            }
        } else if (!tagsNode.asText("").isBlank()) {
            tags.add(tagsNode.asText());
        }
        String single = source.path("query").asText("");
        if (tags.isEmpty() && !single.isBlank()) {
            tags.add(single);
        }
        return new SearchHit(indexName, id, hit.path("_score").asDouble(0), mode, title, url, snippet, tags,
                single, source.path("scraped_at").asText(""));
    }

    /** The reported total, which is an object on 7.x and a plain number elsewhere. */
    private long totalOf(JsonNode json, int fallback) {
        JsonNode total = json.path("hits").path("total");
        if (total.isObject()) {
            return total.path("value").asLong(fallback);
        }
        return total.asLong(fallback);
    }

    /** Reciprocal rank fusion: each list contributes 1 / (k + rank), summed per document. */
    private void collect(JsonNode json, int rrf, Map<String, Double> scores, Map<String, SearchHit> byKey) {
        if (json == null) {
            return;
        }
        JsonNode hits = json.path("hits").path("hits");
        for (int i = 0; i < hits.size(); i++) {
            JsonNode hit = hits.get(i);
            String key = hit.path("_index").asText("") + ":" + hit.path("_id").asText("");
            byKey.putIfAbsent(key, toHit(hit, "hybrid"));
            scores.merge(key, 1.0 / (rrf + i + 1), Double::sum);
        }
    }
}
