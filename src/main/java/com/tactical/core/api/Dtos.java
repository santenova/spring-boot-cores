package com.tactical.core.api;

import java.util.List;

/** Every request and response body the API speaks, in one place. */
public final class Dtos {

    /** Holder only: never instantiated. */
    private Dtos() {
    }

    public record Message(String role, String content) {
    }

    public record ChatRequest(String prompt, List<Message> messages, String model, String persona) {
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
                                Integer resultLimit, Boolean skipSingleTag) {
    }

    public record IngestResult(String index, List<String> log, int indexed, int failed, int vectors) {
    }

    public record Persona(String id, String name, String description, String instructions, String tone,
                          String category, String icon, List<String> expertiseAreas, List<String> tags,
                          String systemPrompt, String source) {
    }

    public record PersonaImportResult(String source, int total, int created, int skipped, List<String> log) {
    }

    public record RagChatRequest(String query, String mode, Integer k, List<String> cores, String persona,
                                 String model, Integer limit) {
    }

    public record RagChatResult(String query, String mode, String answer, List<RagSource> sources,
                                List<SearchHit> hits, String persona, String model, String note,
                                long tookMs) {
    }

    /** One background ingest: its state, the counts it reached and the log lines so far. */
    public record IngestJob(String id, String status, String coreName, List<String> queries, boolean embed,
                            String submittedAt, String startedAt, String finishedAt, int indexed, int failed,
                            int vectors, String error, List<String> log) {
    }
}
