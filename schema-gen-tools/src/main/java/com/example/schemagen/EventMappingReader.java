package com.example.schemagen;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Reflectively reads the {@code @EventMapping} annotation, matched by FQCN so this build-only
 * module keeps no compile dependency on {@code event-contract-kit} (mirrors
 * {@link GenerateSchemaScanner}'s FQCN match). Only the attributes the build-time validator needs
 * are read — {@code schemaType} is deliberately untouched.
 */
final class EventMappingReader {

    /** FQCN of {@code com.example.amqp.topology.mapping.EventMapping} — keep in sync. */
    static final String EVENT_MAPPING_ANNOTATION =
            "com.example.amqp.topology.mapping.EventMapping";

    private EventMappingReader() {}

    /** @return the annotation's raw string attributes, or {@code null} when the type is unannotated. */
    static RawAttributes read(Class<?> type) {
        for (Annotation annotation : type.getAnnotations()) {
            if (EVENT_MAPPING_ANNOTATION.equals(annotation.annotationType().getName())) {
                return new RawAttributes(
                        attr(annotation, "groupId"),
                        attr(annotation, "exchange"),
                        attr(annotation, "routingKey"),
                        attr(annotation, "artifactId"));
            }
        }
        return null;
    }

    private static String attr(Annotation annotation, String name) {
        try {
            Method accessor = annotation.annotationType().getMethod(name);
            return (String) accessor.invoke(annotation);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    "Failed to read @EventMapping." + name + " on "
                            + annotation.annotationType().getName(), e);
        }
    }

    /** Raw, unvalidated annotation attribute values. */
    static final class RawAttributes {

        private final String groupId;
        private final String exchange;
        private final String routingKey;
        private final String artifactId;

        RawAttributes(String groupId, String exchange, String routingKey, String artifactId) {
            this.groupId = groupId;
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.artifactId = artifactId;
        }

        String getGroupId() {
            return groupId;
        }

        String getExchange() {
            return exchange;
        }

        String getRoutingKey() {
            return routingKey;
        }

        String getArtifactId() {
            return artifactId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RawAttributes that)) {
                return false;
            }
            return Objects.equals(groupId, that.groupId)
                    && Objects.equals(exchange, that.exchange)
                    && Objects.equals(routingKey, that.routingKey)
                    && Objects.equals(artifactId, that.artifactId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, exchange, routingKey, artifactId);
        }
    }
}
