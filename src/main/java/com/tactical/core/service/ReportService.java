package com.tactical.core.service;

import com.tactical.core.api.Dtos.CoreInfo;
import com.tactical.core.api.Dtos.IngestJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * One overview of the installation: every core with its document count and vector readiness,
 * the totals across them, and what the ingestion jobs have done so far. It reads only what the
 * other services already know, so it needs no index of its own.
 */
@Service
public class ReportService {

    private final IndexService index;
    private final PersonaService personas;
    private final IngestJobService jobs;

    /** Wires the core metadata, the persona registry and the job history. */
    public ReportService(IndexService index, PersonaService personas, IngestJobService jobs) {
        this.index = index;
        this.personas = personas;
        this.jobs = jobs;
    }

    /** Totals first, then the per-core rows, so a caller can render a summary without summing. */
    public Map<String, Object> overview() {
        List<CoreInfo> cores = index.listCores();
        List<Map<String, Object>> rows = new ArrayList<>();
        long documents = 0;
        long withVectors = 0;
        for (CoreInfo core : cores) {
            documents += core.documents();
            if (core.hasVectors()) {
                withVectors++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", core.index());
            row.put("label", core.label());
            row.put("documents", core.documents());
            row.put("hasVectors", core.hasVectors());
            row.put("dims", core.dims());
            rows.add(row);
        }
        List<IngestJob> history = jobs.list();
        long indexed = 0;
        long failed = 0;
        long running = 0;
        for (IngestJob job : history) {
            indexed += job.indexed();
            failed += job.failed();
            if ("queued".equals(job.status()) || "running".equals(job.status())) {
                running++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());
        body.put("coreCount", cores.size());
        body.put("documents", documents);
        body.put("coresWithVectors", withVectors);
        body.put("sourceIndices", index.sourceIndices().size());
        body.put("personas", personas.list().size());
        body.put("ingestJobs", history.size());
        body.put("ingestRunning", running);
        body.put("ingestIndexed", indexed);
        body.put("ingestFailed", failed);
        body.put("cores", rows);
        return body;
    }
}
