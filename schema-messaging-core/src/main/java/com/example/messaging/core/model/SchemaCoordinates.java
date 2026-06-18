package com.example.messaging.core.model;

/**
 * Immutable key identifying a schema artifact version in Apicurio Registry.
 * {@code version} is nullable; null means "latest".
 * Used as schema-memo cache key — records provide correct equals/hashCode.
 */
public record SchemaCoordinates(String groupId, String artifactId, String version) {

    /** Convenience factory for "latest" version. */
    public static SchemaCoordinates latest(String groupId, String artifactId) {
        return new SchemaCoordinates(groupId, artifactId, null);
    }

    /** Version expression for Apicurio API calls (null → "branch=latest" per Apicurio 3.x). */
    public String versionExpression() {
        return version != null ? version : "branch=latest";
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + versionExpression();
    }
}
