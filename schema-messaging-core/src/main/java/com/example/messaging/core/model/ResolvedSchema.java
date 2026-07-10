package com.example.messaging.core.model;

/**
 * A schema entry loaded from the classpath by {@link com.example.messaging.core.schema.LocalSchemaCatalog}.
 */
public record ResolvedSchema(
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        byte[] rawContent
) {}
