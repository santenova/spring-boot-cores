package com.tactical.core.service;

import com.tactical.core.api.ApiException;
import com.tactical.core.api.Dtos.IngestJob;
import com.tactical.core.api.Dtos.IngestRequest;
import com.tactical.core.api.Dtos.IngestResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;

/**
 * Ingestion as a background job. Submitting answers at once with an id, one worker thread
 * builds the core, and the registry reports the state until it finishes, so a long ingest can
 * be polled instead of holding a request open for the whole run. The registry lives in memory:
 * restart the service and the history is gone, while the ingested documents stay.
 */
@Service
public class IngestJobService {

    /** Finished jobs kept for the status list; older ones are dropped as new ones arrive. */
    private static final int KEEP = 50;

    private final IngestService ingest;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ingest-job");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Future<?>> running = new LinkedHashMap<>();

    /** One job's mutable state; the API only ever sees an immutable snapshot of it. */
    private static final class Job {
        private final String id = UUID.randomUUID().toString().substring(0, 8);
        private final String coreName;
        private final List<String> queries;
        private final boolean embed;
        private final Instant submittedAt = Instant.now();
        private volatile String status = "queued";
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile int indexed;
        private volatile int failed;
        private volatile int vectors;
        private volatile String error;
        private volatile List<String> log = new ArrayList<>();

        private Job(String coreName, List<String> queries, boolean embed) {
            this.coreName = coreName;
            this.queries = List.copyOf(queries);
            this.embed = embed;
        }

        private IngestJob snapshot() {
            return new IngestJob(id, status, coreName, queries, embed,
                    submittedAt.toString(),
                    startedAt == null ? null : startedAt.toString(),
                    finishedAt == null ? null : finishedAt.toString(),
                    indexed, failed, vectors, error, log);
        }
    }

    /** Wires the service that does the work. */
    public IngestJobService(IngestService ingest) {
        this.ingest = ingest;
    }

    /** Queues one ingest and answers with the job as it stands, which is queued. */
    public IngestJob submit(IngestRequest request) {
        if (request.queries() == null || request.queries().isEmpty()) {
            throw new ApiException(400, "At least one query is required");
        }
        Job job = new Job(request.coreName(), request.queries(),
                request.embed() == null || request.embed());
        synchronized (jobs) {
            jobs.put(job.id, job);
            trim();
            running.put(job.id, worker.submit(() -> execute(job, request)));
        }
        return job.snapshot();
    }

    /** Every job, newest first. */
    public List<IngestJob> list() {
        synchronized (jobs) {
            List<IngestJob> out = new ArrayList<>();
            for (Job job : jobs.values()) {
                out.add(0, job.snapshot());
            }
            return out;
        }
    }

    /** One job by id, 404 when the id was never submitted. */
    public IngestJob get(String id) {
        return find(id).snapshot();
    }

    /** Asks the worker to stop and marks the job cancelled. */
    public IngestJob cancel(String id) {
        Job job = find(id);
        synchronized (jobs) {
            Future<?> future = running.get(id);
            if (future != null) {
                future.cancel(true);
            }
        }
        if (!"done".equals(job.status) && !"failed".equals(job.status)) {
            job.status = "cancelled";
            job.finishedAt = Instant.now();
        }
        return job.snapshot();
    }

    private Job find(String id) {
        synchronized (jobs) {
            Job job = jobs.get(id);
            if (job == null) {
                throw new ApiException(404, "No such ingest job: " + id);
            }
            return job;
        }
    }

    /** The worker body: run the ingest, keep its counts and log, never let a failure escape. */
    private void execute(Job job, IngestRequest request) {
        job.status = "running";
        job.startedAt = Instant.now();
        try {
            IngestResult result = ingest.run(request);
            job.log = result.log();
            job.indexed = result.indexed();
            job.failed = result.failed();
            job.vectors = result.vectors();
            job.status = "done";
        } catch (RuntimeException error) {
            job.error = String.valueOf(error.getMessage());
            job.status = "failed";
        } finally {
            job.finishedAt = Instant.now();
            synchronized (jobs) {
                running.remove(job.id);
            }
        }
    }

    /** Drops the oldest finished job once the list is longer than it needs to be. */
    private void trim() {
        while (jobs.size() > KEEP) {
            String oldest = null;
            for (Job job : jobs.values()) {
                if ("done".equals(job.status) || "failed".equals(job.status)
                        || "cancelled".equals(job.status)) {
                    oldest = job.id;
                    break;
                }
            }
            if (oldest == null) {
                return;
            }
            jobs.remove(oldest);
        }
    }
}
