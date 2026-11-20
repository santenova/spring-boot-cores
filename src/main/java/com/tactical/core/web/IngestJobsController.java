package com.tactical.core.web;

import com.tactical.core.api.Dtos.IngestJob;
import com.tactical.core.api.Dtos.IngestRequest;
import com.tactical.core.service.IngestJobService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest/jobs")
public class IngestJobsController {

    private final IngestJobService jobs;

    /** Wires the job registry. */
    public IngestJobsController(IngestJobService jobs) {
        this.jobs = jobs;
    }

    /** Queues an ingest and answers 202 with the job, so a long run never holds the request. */
    @PostMapping
    public ResponseEntity<IngestJob> submit(@RequestBody IngestRequest request) {
        return ResponseEntity.accepted().body(jobs.submit(request));
    }

    /** Every job, newest first, for a status list. */
    @GetMapping
    public List<IngestJob> list() {
        return jobs.list();
    }

    @GetMapping("/{id}")
    /** One job's state, counts and log lines so far. */
    public IngestJob get(@PathVariable String id) {
        return jobs.get(id);
    }

    /** Stops a running job and confirms the state it was left in. */
    @DeleteMapping("/{id}")
    public IngestJob cancel(@PathVariable String id) {
        return jobs.cancel(id);
    }
}
