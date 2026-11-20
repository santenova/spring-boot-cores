package com.tactical.core.web;

import com.tactical.core.service.ReportService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;

    /** Wires the overview report. */
    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Cores, documents, vectors, personas and ingest totals in one response. */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return reports.overview();
    }
}
