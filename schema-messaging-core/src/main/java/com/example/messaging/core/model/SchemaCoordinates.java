package com.example.messaging.core.model;

/**
 * Identifies a schema artifact: the same {@code groupId}/{@code artifactId} pair used as the
 * CI-governance identity by {@code apicurio-registry-maven-plugin} (register/compat-check), reused
 * at runtime purely as a local lookup key into {@link com.example.messaging.core.schema.LocalSchemaCatalog}
 * — no registry call is made. Used as a map key — records provide correct equals/hashCode.
 */
public record SchemaCoordinates(String groupId, String artifactId) {

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
