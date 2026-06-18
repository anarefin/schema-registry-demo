package com.example.messaging.core.model;

/**
 * A fully resolved schema entry held in the in-memory schema memo
 * (globalId + wire format + raw schema content).
 */
public record ResolvedSchema(
        long globalId,
        SchemaType schemaType,
        byte[] rawContent
) {
}
