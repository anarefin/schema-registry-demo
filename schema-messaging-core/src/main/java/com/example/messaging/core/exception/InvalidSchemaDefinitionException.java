package com.example.messaging.core.exception;

/**
 * Schema content on the classpath is present but cannot be compiled/parsed by the
 * {@link com.example.messaging.core.serde.SerializationStrategy} (ADR-0004).
 * Thrown at startup from {@code SerializationStrategy#warm} — not a message-time path.
 */
public class InvalidSchemaDefinitionException extends SchemaMessagingException {

    public InvalidSchemaDefinitionException(String coordinates, String detail, Throwable cause) {
        super("Invalid schema definition for " + coordinates + ": " + detail, coordinates, cause);
    }

    public InvalidSchemaDefinitionException(String coordinates, String detail) {
        super("Invalid schema definition for " + coordinates + ": " + detail, coordinates);
    }
}
