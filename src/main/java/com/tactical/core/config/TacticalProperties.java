package com.tactical.core.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable of the port. Records bind by canonical constructor, so a missing key fails
 * fast at startup instead of quietly defaulting to zero.
 */
@ConfigurationProperties(prefix = "tactical")
public record TacticalProperties(Ollama ollama, Elastic elastic, Search search, Rag rag, Personas personas,
                                 Ingest ingest) {

    public record Ollama(String baseUrl, String apiKey, String chatModel, String embeddingModel,
                         Duration connectTimeout, Duration readTimeout) {
    }

    public record Elastic(String active, String indexPrefix, List<Endpoint> endpoints) {
    }

    public record Endpoint(String label, String url, String username, String password) {
    }

    public record Search(int defaultTopK, int maxTopK, int wideNetLimit, int rrfK, int numCandidatesFactor) {
    }

    public record Rag(int sourceLimit, int maxCharsPerSource, int maxSentences) {
    }

    /** sampleFile is a classpath: or filesystem path; defaultPersona may be empty. */
    public record Personas(String sampleFile, boolean autoImport, String defaultPersona) {
    }

    /** auto carries the scheduled re-ingestion switch, its target core and its topics. */
    public record Ingest(Auto auto) {

        public record Auto(boolean enabled, String coreName, boolean embed, List<String> queries) {
        }
    }
}
