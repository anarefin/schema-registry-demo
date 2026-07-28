package com.example.schemagen;

import java.util.Objects;

/**
 * Validated, effective metadata for one code-first event class, produced by
 * {@link EventMappingValidator}.
 */
public final class EventMappingMetadata {

    private final Class<?> javaType;
    private final String fqcn;
    private final String groupId;
    private final String artifactId;
    private final String beanName;

    public EventMappingMetadata(
            Class<?> javaType,
            String fqcn,
            String groupId,
            String artifactId,
            String beanName) {
        this.javaType = javaType;
        this.fqcn = fqcn;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.beanName = beanName;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public String getFqcn() {
        return fqcn;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getBeanName() {
        return beanName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventMappingMetadata that)) {
            return false;
        }
        return Objects.equals(fqcn, that.fqcn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fqcn);
    }

    @Override
    public String toString() {
        return "EventMappingMetadata{fqcn='" + fqcn + "', groupId='" + groupId
                + "', artifactId='" + artifactId + "', beanName='" + beanName + "'}";
    }
}
