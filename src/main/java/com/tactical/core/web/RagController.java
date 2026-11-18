package com.tactical.core.web;

import com.tactical.core.api.Dtos.RagRequest;
import com.tactical.core.api.Dtos.RagResult;
import com.tactical.core.service.RagService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService rag;

    /** Wires the synthesis service. */
    public RagController(RagService rag) {
        this.rag = rag;
    }

    /** Post the hits returned by /api/search; each is resolved back to its full source. */
    @PostMapping("/synthesize")
    public RagResult synthesize(@RequestBody RagRequest request) {
        return rag.synthesize(request);
    }
}
