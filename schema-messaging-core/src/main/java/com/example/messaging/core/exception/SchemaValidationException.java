package com.example.messaging.core.exception;

import java.util.List;

/**
 * Payload fails validation against its registered schema (spec App. B).
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class SchemaValidationException extends SchemaMessagingException implements PermanentFailure {

    private final List<String> validationErrors;

    public SchemaValidationException(String coordinates, List<String> errors) {
        super("Schema validation failed for " + coordinates + ": " + String.join("; ", errors), coordinates);
        this.validationErrors = List.copyOf(errors);
    }

    public SchemaValidationException(String coordinates, String detail) {
        this(coordinates, List.of(detail));
    }

    public SchemaValidationException(String coordinates, String detail, Throwable cause) {
        super("Schema validation failed for " + coordinates + ": " + detail, coordinates, cause);
        this.validationErrors = List.of(detail);
    }

    /** Individual validation error messages; never empty. */
    public List<String> validationErrors() {
        return validationErrors;
    }
}
