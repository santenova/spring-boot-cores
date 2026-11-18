package com.tactical.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.RagRequest;
import com.tactical.core.api.Dtos.RagResult;
import com.tactical.core.api.Dtos.RagSource;
import com.tactical.core.api.Dtos.SearchHit;
import com.tactical.core.config.TacticalProperties;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * RAG synthesis. Every hit is traced back to the document it was ingested from, so the model
 * reads the whole source instead of the truncated core snippet; a hit whose original cannot
 * be found keeps its snippet and says so.
 */
@Service
public class RagService {

    /** A source needs more tags than this: a thinly tagged hit was matched on too little. */
    private static final int MIN_TAGS = 3;

    private record Resolved(String text, String from) {
    }

    private final EsClient es;
    private final IndexService index;
    private final OllamaClient ollama;
    private final TacticalProperties props;

    /** Wires the cluster, the core metadata, the model client and the limits. */
    public RagService(EsClient es, IndexService index, OllamaClient ollama, TacticalProperties props) {
        this.es = es;
        this.index = index;
        this.ollama = ollama;
        this.props = props;
    }

    /** Resolves the top hits to their originals, asks the model, returns answer plus sources. */
    public RagResult synthesize(RagRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new ApiException(400, "A query is required");
        }
        if (request.hits() == null || request.hits().isEmpty()) {
            throw new ApiException(400, "At least one search hit is required");
        }
        // Only a document carrying more than three tags is trusted as a source: with fewer it
        // was matched on too little to synthesise from, and would only dilute the answer
        List<SearchHit> eligible = new ArrayList<>();
        for (SearchHit hit : request.hits()) {
            if (hit.tags() != null && hit.tags().size() > MIN_TAGS) {
                eligible.add(hit);
            }
        }
        if (eligible.isEmpty()) {
            throw new ApiException(409, "No hit carries more than " + MIN_TAGS
                    + " tags - nothing to synthesise");
        }
        int skipped = request.hits().size() - eligible.size();
        int limit = request.limit() == null
                ? props.rag().sourceLimit()
                : Math.min(request.limit(), 50);
        List<String> sourceIndices = index.sourceIndices();
        List<RagSource> sources = new ArrayList<>();
        int full = 0;
        for (int i = 0; i < Math.min(limit, eligible.size()); i++) {
            SearchHit hit = eligible.get(i);
            Resolved resolved = resolve(hit, sourceIndices);
            if ("original".equals(resolved.from())) {
                full++;
            }
            sources.add(new RagSource(i + 1, hit.title(), hit.url(), resolved.text(), resolved.from()));
        }

        StringBuilder context = new StringBuilder();
        for (RagSource source : sources) {
            context.append('[').append(source.n()).append("] ").append(source.title()).append('\n')
                    .append(source.text()).append("\n\n");
        }
        String prompt = "You are a research analyst. Answer the question using ONLY the numbered original documents below.\n"
                + "Cite every claim inline with its source number, like [1] or [2][3].\n"
                + "If the sources do not contain the answer, reply exactly: \"The indexed sources do not cover this.\"\n"
                + "Be concise (maximum " + props.rag().maxSentences() + " sentences).\n\n"
                + "QUESTION: " + query + "\n\nSOURCES:\n" + context;
        String answer = ollama.chat(List.of(Map.of("role", "user", "content", prompt)),
                props.ollama().chatModel(), false);
        String note = "SYNTHESIS CONTEXT: " + full + " OF " + sources.size()
                + " SOURCES READ AS FULL ORIGINAL DOCUMENTS"
                + (full < sources.size() ? " - THE REST HAVE ONLY THEIR SNIPPET" : "")
                + (skipped > 0 ? " | " + skipped + " HIT" + (skipped == 1 ? "" : "S")
                        + " SKIPPED: FEWER THAN " + (MIN_TAGS + 1) + " TAGS" : "");
        return new RagResult(answer, sources, note);
    }

    /** Original document for one hit: by es:// address, by url, else the stored snippet. */
    private Resolved resolve(SearchHit hit, List<String> sourceIndices) {
        int max = props.rag().maxCharsPerSource();
        String snippet = hit.snippet() == null ? "" : hit.snippet();
        if (snippet.length() > max) {
            snippet = snippet.substring(0, max);
        }
        Resolved fallback = new Resolved(snippet, "snippet");

        String url = hit.url() == null ? "" : hit.url();
        if (url.startsWith("es://")) {
            String rest = url.substring(5);
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String indexName = rest.substring(0, slash);
                String id = rest.substring(slash + 1);
                if (!indexName.startsWith(index.prefix())) {
                    try {
                        JsonNode doc = es.get("/" + EsClient.enc(indexName) + "/_doc/" + EsClient.enc(id));
                        String text = documentText(doc.path("_source"));
                        if (!text.isBlank()) {
                            return new Resolved(text, "original");
                        }
                    } catch (RuntimeException ignored) {
                        // a refused lookup is not fatal - the snippet still answers
                    }
                }
            }
        }
        if (!sourceIndices.isEmpty() && url.startsWith("http") && url.length() < 2048) {
            List<Map<String, Object>> should = new ArrayList<>();
            should.add(Map.of("term", Map.of("url", url)));
            should.add(Map.of("match_phrase", Map.of("url", url)));
            Map<String, Object> bool = new LinkedHashMap<>();
            bool.put("should", should);
            bool.put("minimum_should_match", 1);
            try {
                JsonNode json = es.post("/" + String.join(",", sourceIndices) + "/_search",
                        Map.of("size", 1, "query", Map.of("bool", bool)));
                String text = documentText(json.path("hits").path("hits").path(0).path("_source"));
                if (!text.isBlank()) {
                    return new Resolved(text, "original");
                }
            } catch (RuntimeException ignored) {
                // same as above
            }
        }
        return fallback;
    }

    /** The longest readable string in a stored document, cut to the configured limit. */
    private String documentText(JsonNode source) {
        List<String> strings = new ArrayList<>();
        collectStrings(source, strings, 0);
        String best = "";
        for (String candidate : strings) {
            if (candidate.length() > best.length()) {
                best = candidate;
            }
        }
        int max = props.rag().maxCharsPerSource();
        return best.length() > max ? best.substring(0, max) : best;
    }

    /** Walks a document and collects its substantial strings, bounded by depth. */
    private void collectStrings(JsonNode node, List<String> out, int depth) {
        if (node == null || node.isMissingNode() || depth > 5) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.length() > 30 && !looksLikePath(text)) {
                out.add(text);
            }
            return;
        }
        if (node.isArray() || node.isObject()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                collectStrings(children.next(), out, depth + 1);
            }
        }
    }

    /**
     * A stored field that is really a file path or a URI rather than prose. A crawler-fed
     * document often carries its local path in one field, and that path is frequently the
     * longest string in the record: read as the body it tells the model nothing and displaces
     * the snippet that does, so it is skipped and the snippet stands in.
     */
    private static boolean looksLikePath(String text) {
        String value = text.trim();
        if (value.contains("://")) {
            return true;
        }
        String last = value.substring(value.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        if (value.indexOf('/') >= 0 && dot > 0 && last.length() - dot <= 6 && last.indexOf(' ') < 0) {
            return true;
        }
        boolean rootLike = value.startsWith("/") || value.startsWith("~/")
                || (value.length() > 1 && value.charAt(1) == ':');
        return rootLike && value.indexOf(' ') < 0 && value.length() > 20;
    }
}
