package com.example.messaging.core.model;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;

/**
 * A schema entry loaded from the classpath by {@link com.example.messaging.core.schema.LocalSchemaCatalog}.
 */
public record ResolvedSchema(
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        byte[] rawContent
) {}
