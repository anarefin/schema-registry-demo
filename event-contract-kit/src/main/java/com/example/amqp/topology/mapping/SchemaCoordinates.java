package com.example.amqp.topology.mapping;

import java.util.Objects;

/**
 * Identifies a schema artifact: the same {@code groupId}/{@code artifactId} pair used as the
 * CI-governance identity by {@code apicurio-registry-maven-plugin} (register/compat-check), reused
 * at runtime purely as a local lookup key into schema-messaging-core's local schema catalog — no
 * registry call is made. Used as a map key — correct equals/hashCode required.
 */
public final class SchemaCoordinates {

    private final String groupId;
    private final String artifactId;

    public SchemaCoordinates(String groupId, String artifactId) {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SchemaCoordinates that)) {
            return false;
        }
        return Objects.equals(groupId, that.groupId)
                && Objects.equals(artifactId, that.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId);
    }
}
