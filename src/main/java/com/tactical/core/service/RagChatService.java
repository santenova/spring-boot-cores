package com.tactical.core.service;

import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.Persona;
import com.tactical.core.api.Dtos.RagChatRequest;
import com.tactical.core.api.Dtos.RagChatResult;
import com.tactical.core.api.Dtos.RagRequest;
import com.tactical.core.api.Dtos.RagResult;
import com.tactical.core.api.Dtos.SearchRequest;
import com.tactical.core.api.Dtos.SearchResult;
import com.tactical.core.config.TacticalProperties;
import org.springframework.stereotype.Service;

/**
 * The whole RAG turn behind one request: the query is searched, every hit is resolved back to
 * the original document it came from, and the model answers with numbered citations. A persona
 * and a model may both be named, and the response carries the hits as well as the sources they
 * were read from, so a client can show what the answer stands on.
 */
@Service
public class RagChatService {

    private final SearchService search;
    private final RagService rag;
    private final PersonaService personas;
    private final TacticalProperties props;

    /** Wires retrieval, synthesis, the persona registry and the configured model. */
    public RagChatService(SearchService search, RagService rag, PersonaService personas,
                          TacticalProperties props) {
        this.search = search;
        this.rag = rag;
        this.personas = personas;
        this.props = props;
    }

    /** Searches, then synthesises; an empty result is refused instead of answered from nothing. */
    public RagChatResult chat(RagChatRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new ApiException(400, "A query is required");
        }
        String mode = request.mode() == null || request.mode().isBlank()
                ? "hybrid"
                : request.mode().toLowerCase();
        long started = System.currentTimeMillis();
        SearchResult found = search.search(new SearchRequest(query, mode, request.k(), request.cores(), null));
        if (found.hits().isEmpty()) {
            throw new ApiException(404, "No hits for this query - nothing to synthesise");
        }
        Persona persona = persona(request.persona());
        String model = request.model() == null || request.model().isBlank()
                ? props.ollama().chatModel()
                : request.model();
        RagResult answer = rag.synthesize(new RagRequest(query, found.hits(), request.limit()),
                persona == null ? null : persona.systemPrompt(), model);
        return new RagChatResult(query, mode, answer.answer(), answer.sources(), found.hits(),
                persona == null ? null : persona.id(), model, answer.note(),
                System.currentTimeMillis() - started);
    }

    /** The persona this turn runs as: the one named, or none. An unknown id is refused. */
    private Persona persona(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return personas.require(id);
    }
}
