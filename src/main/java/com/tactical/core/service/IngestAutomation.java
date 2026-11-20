package com.tactical.core.service;

import com.tactical.core.api.Dtos.IngestJob;
import com.tactical.core.api.Dtos.IngestRequest;
import com.tactical.core.config.TacticalProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Re-ingestion on a timer. With tactical.ingest.auto.enabled true, every interval submits one
 * job for the configured core and topics, so a core keeps up with its sources without anyone
 * calling the API. The tick is silent when the switch is off, which is the default.
 */
@Service
public class IngestAutomation {

    private static final Logger log = LoggerFactory.getLogger(IngestAutomation.class);

    private final IngestJobService jobs;
    private final TacticalProperties props;

    /** Wires the job registry and the schedule settings. */
    public IngestAutomation(IngestJobService jobs, TacticalProperties props) {
        this.jobs = jobs;
        this.props = props;
    }

    /** One scheduled tick: queue the configured ingest, or do nothing when it is switched off. */
    @Scheduled(fixedDelayString = "${tactical.ingest.auto.delay-ms:900000}",
            initialDelayString = "${tactical.ingest.auto.initial-delay-ms:60000}")
    public void tick() {
        TacticalProperties.Ingest ingest = props.ingest();
        TacticalProperties.Ingest.Auto auto = ingest == null ? null : ingest.auto();
        if (auto == null || !auto.enabled()) {
            return;
        }
        List<String> queries = auto.queries();
        if (queries == null || queries.isEmpty()) {
            log.warn("Scheduled ingestion is on but tactical.ingest.auto.queries is empty");
            return;
        }
        try {
            IngestJob job = jobs.submit(new IngestRequest(queries, auto.coreName(), true, auto.embed(), null, null));
            log.info("Scheduled ingestion queued as job {} for core {}", job.id(), job.coreName());
        } catch (RuntimeException error) {
            log.warn("Scheduled ingestion could not be queued: {}", error.getMessage());
        }
    }
}
