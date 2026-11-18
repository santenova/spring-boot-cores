package com.tactical.core.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    /** Renders a deliberate failure with its own status and a readable message. */
    public ResponseEntity<Map<String, Object>> handleApi(ApiException error) {
        log.warn("{}", error.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getMessage());
        body.put("status", error.getStatus());
        return ResponseEntity.status(error.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    /** Catches anything unexpected, logs it, and answers 500 instead of a stack trace. */
    public ResponseEntity<Map<String, Object>> handleOther(Exception error) {
        log.error("Unhandled error", error);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", String.valueOf(error.getMessage()));
        body.put("status", 500);
        return ResponseEntity.status(500).body(body);
    }
}
