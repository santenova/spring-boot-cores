package com.tactical.core.api;

import java.util.List;

/** Every request and response body the API speaks, in one place. */
public final class Dtos {

    /** Holder only: never instantiated. */
    private Dtos() {
    }

    public record Message(String role, String content) {
    }

    public record ChatRequest(String prompt, List<Message> messages, String model) {
    }

    public record SearchRequest(String query, String mode, Integer k, List<String> cores, List<String> tags) {
    }

    public record SearchHit(String index, String id, double score, String mode, String title, String url,
                            String snippet, List<String> tags, String query, String scrapedAt) {

        /** A copy of this hit carrying a fused score, since a record cannot be mutated. */
        public SearchHit withScore(double newScore) {
            return new SearchHit(index, id, newScore, mode, title, url, snippet, tags, query, scrapedAt);
        }
    }

    public record SearchResult(List<SearchHit> hits, long total, long tookMs, String scopeNote) {
    }

    public record TagCount(String tag, long count) {
    }

    public record CoreInfo(String index, String label, long documents, boolean hasVectors, Integer dims) {
    }

    public record RagRequest(String query, List<SearchHit> hits, Integer limit) {
    }

    public record RagSource(int n, String title, String url, String text, String from) {
    }

    public record RagResult(String answer, List<RagSource> sources, String note) {
    }

    public record IngestRequest(List<String> queries, String coreName, Boolean expandQueries, Boolean embed,
                                Integer resultLimit) {
    }

    public record IngestResult(String index, List<String> log, int indexed, int failed, int vectors) {
    }
}
