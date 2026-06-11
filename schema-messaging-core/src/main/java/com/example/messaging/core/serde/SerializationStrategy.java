package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;

/**
 * SPI for format-specific serialization, validation, and deserialization (spec §10.1 / T-1.6).
 *
 * <p>Implementation: {@link JsonSchemaStrategy} (networknt validation + Jackson) — the sole
 * built-in strategy; the wire format is the raw JSON document only (spec §6).
 *
 * <p>To add a new format (e.g. Avro), implement this interface and register the bean with Spring.
 * Override {@link #contentType()} if the MIME type differs from the {@link SchemaType} default.
 */
public interface SerializationStrategy {

    /** Schema type this strategy handles. */
    SchemaType schemaType();

    /**
     * MIME content-type for the wire format, used to populate the AMQP content-type header.
     * Defaults to {@link SchemaType#contentType()}; override when the strategy uses a
     * non-standard variant (e.g. {@code application/avro-binary}).
     */
    default String contentType() {
        return schemaType().contentType();
    }

    /**
     * Validate {@code payload} against {@code schema} then serialize to raw bytes.
     * Throws {@link SchemaValidationException} if invalid (no bytes produced).
     * Throws {@link SerializationException} on encoding failure.
     */
    byte[] serialize(Object payload, ResolvedSchema schema)
            throws SchemaValidationException, SerializationException;

    /**
     * Deserialize raw bytes to an instance of {@code targetType}.
     * Throws {@link DeserializationException} on parse failure.
     */
    Object deserialize(byte[] bytes, Class<?> targetType, ResolvedSchema schema)
            throws DeserializationException;
}
