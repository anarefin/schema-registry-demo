package com.example.messaging.core.model;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;

import java.util.Arrays;
import java.util.Objects;

/**
 * A schema entry loaded from the classpath by {@link com.example.messaging.core.schema.LocalSchemaCatalog}.
 */
public final class ResolvedSchema {

    private final SchemaCoordinates coordinates;
    private final SchemaType schemaType;
    private final byte[] rawContent;

    public ResolvedSchema(SchemaCoordinates coordinates, SchemaType schemaType, byte[] rawContent) {
        this.coordinates = coordinates;
        this.schemaType = schemaType;
        this.rawContent = rawContent.clone();
    }

    public SchemaCoordinates getCoordinates() {
        return coordinates;
    }

    public SchemaType getSchemaType() {
        return schemaType;
    }

    public byte[] getRawContent() {
        return rawContent.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResolvedSchema that)) {
            return false;
        }
        return Objects.equals(coordinates, that.coordinates)
                && schemaType == that.schemaType
                && Arrays.equals(rawContent, that.rawContent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coordinates, schemaType, Arrays.hashCode(rawContent));
    }

    @Override
    public String toString() {
        return "ResolvedSchema[coordinates=" + coordinates
                + ", schemaType=" + schemaType
                + ", rawContent=" + Arrays.toString(rawContent) + "]";
    }
}
