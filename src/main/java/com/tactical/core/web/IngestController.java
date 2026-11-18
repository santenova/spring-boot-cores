package com.tactical.core.web;

import com.tactical.core.api.Dtos.IngestRequest;
import com.tactical.core.api.Dtos.IngestResult;
import com.tactical.core.service.IngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestService ingest;

    /** Wires the ingestion service. */
    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping
    /** Builds one core from the given topics and returns the ingestion log. */
    public IngestResult run(@RequestBody IngestRequest request) {
        return ingest.run(request);
    }
}
