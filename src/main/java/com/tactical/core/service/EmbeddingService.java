package com.tactical.core.service;

import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    private static final int FALLBACK_DIMS = 384;

    private final OllamaClient ollama;
    private volatile int dims = 0;

    /** Takes the model client; the vector width is discovered on first use. */
    public EmbeddingService(OllamaClient ollama) {
        this.ollama = ollama;
    }

    /** Embeds one text and remembers the width it came back with. */
    public double[] embed(String text) {
        double[] vector = ollama.embed(text);
        if (vector.length > 0) {
            dims = vector.length;
        }
        return vector;
    }

    /**
     * The width the configured model produces, probed once and cached. A dense_vector mapping
     * written at the wrong width can only be corrected by re-indexing, so the probe runs
     * before any core is created.
     */
    public int dims() {
        if (dims == 0) {
            try {
                embed("dimension probe");
            } catch (RuntimeException error) {
                dims = FALLBACK_DIMS;
            }
        }
        return dims == 0 ? FALLBACK_DIMS : dims;
    }
}
