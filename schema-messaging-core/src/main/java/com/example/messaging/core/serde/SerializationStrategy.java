package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;

/**
 * SPI for format-specific serialization, validation, and deserialization (spec §10.1 / T-1.6).
 *
 * <p>Implementations: {@link ProtobufStrategy} (binary, no magic byte/length prefix — spec §6),
 * {@link JsonSchemaStrategy} (networknt validation + Jackson).
 */
public interface SerializationStrategy {

    /** Schema type this strategy handles. */
    SchemaType schemaType();

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
