package com.example.messaging.core.mapping;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.TypeMapping;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Collects all service-contributed {@link TypeMapping} beans and provides O(1) lookups
 * by Java type (producer path) and by {@link SchemaCoordinates} (consumer path).
 */
public class TypeMappingRegistry {

    private final List<TypeMapping> all;
    private final Map<Class<?>, TypeMapping> byJavaType;
    private final Map<SchemaCoordinates, TypeMapping> byCoordinates;

    public TypeMappingRegistry(List<TypeMapping> mappings) {
        this.all = Collections.unmodifiableList(mappings);
        this.byJavaType = indexBy(mappings, TypeMapping::getJavaType, "java type");
        this.byCoordinates = indexBy(mappings, TypeMapping::getCoordinates, "coordinates");
    }

    private static <K> Map<K, TypeMapping> indexBy(
            List<TypeMapping> mappings,
            Function<TypeMapping, K> keyFn,
            String keyLabel) {
        Map<K, TypeMapping> map = new java.util.LinkedHashMap<>();
        for (TypeMapping mapping : mappings) {
            K key = keyFn.apply(mapping);
            TypeMapping previous = map.put(key, mapping);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate TypeMapping " + keyLabel + ": " + key
                        + " (already mapped to " + previous.getJavaType().getName()
                        + ", also " + mapping.getJavaType().getName() + ")");
            }
        }
        return Map.copyOf(map);
    }

    public List<TypeMapping> all() {
        return all;
    }

    public Optional<TypeMapping> findByJavaType(Class<?> javaType) {
        return Optional.ofNullable(byJavaType.get(javaType));
    }

    public Optional<TypeMapping> findByCoordinates(SchemaCoordinates coordinates) {
        return Optional.ofNullable(byCoordinates.get(coordinates));
    }

    /** Convenience: lookup by group/artifact, delegating to the coordinates map. */
    public Optional<TypeMapping> findByGroupAndArtifact(String groupId, String artifactId) {
        return findByCoordinates(new SchemaCoordinates(groupId, artifactId));
    }
}
