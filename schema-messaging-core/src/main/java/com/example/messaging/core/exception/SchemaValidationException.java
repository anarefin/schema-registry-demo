package com.example.messaging.core.exception;

/**
 * Payload fails validation against its registered schema (spec App. B).
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class SchemaValidationException extends SchemaMessagingException {

    public SchemaValidationException(String coordinates, String detail) {
        super("Schema validation failed for " + coordinates + ": " + detail, coordinates);
    }

    public SchemaValidationException(String coordinates, String detail, Throwable cause) {
        super("Schema validation failed for " + coordinates + ": " + detail, coordinates, cause);
    }
}
