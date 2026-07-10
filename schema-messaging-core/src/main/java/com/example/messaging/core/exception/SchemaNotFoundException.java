package com.example.messaging.core.exception;

/**
 * Schema classpath resource is missing or unreadable (spec App. B / ADR-0004).
 * Thrown at startup by {@code LocalSchemaCatalog} — not a message-time failure path.
 */
public class SchemaNotFoundException extends SchemaMessagingException {

    public SchemaNotFoundException(String coordinates) {
        super("Schema not found: " + coordinates, coordinates);
    }

    public SchemaNotFoundException(String coordinates, Throwable cause) {
        super("Schema not found: " + coordinates, coordinates, cause);
    }
}
