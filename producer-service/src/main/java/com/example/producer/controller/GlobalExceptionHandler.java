package com.example.producer.controller;

import com.example.messaging.core.exception.SchemaValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Shared REST exception mapping for the producer service.
 *
 * <p>The {@code EventPublisher} validates payloads against the JSON Schema before sending, so
 * invalid input surfaces as a {@link SchemaValidationException} and is mapped to 400 — no message
 * is emitted (spec §5 / TC-5.9).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<String> handleValidation(SchemaValidationException ex) {
        log.warn("Schema validation failed: {}", ex.getMessage());
        return ResponseEntity.badRequest().body("Schema validation failed: " + ex.getMessage());
    }
}
