package com.tactical.core.web;

import com.tactical.core.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService export;

    /** Wires the export service. */
    public ExportController(ExportService export) {
        this.export = export;
    }

    /**
     * format=ndjson (default) keeps every field, format=csv is the spreadsheet shape. The file
     * is named after the core, so a browser saves it rather than rendering it.
     */
    @GetMapping("/cores/{name}")
    public ResponseEntity<String> core(@PathVariable String name,
                                       @RequestParam(defaultValue = "ndjson") String format,
                                       @RequestParam(defaultValue = "1000") int limit) {
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(export.csv(name, limit));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".ndjson\"")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(export.ndjson(name, limit));
    }
}
