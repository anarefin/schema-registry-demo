package com.example.messaging.core.exception;

/** Schema artifact or version does not exist in the registry (spec App. B). */
public class SchemaNotFoundException extends SchemaMessagingException {

    public SchemaNotFoundException(String coordinates) {
        super("Schema not found: " + coordinates, coordinates);
    }

    public SchemaNotFoundException(String coordinates, Throwable cause) {
        super("Schema not found: " + coordinates, coordinates, cause);
    }
}
