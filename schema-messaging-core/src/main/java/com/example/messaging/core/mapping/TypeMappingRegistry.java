package com.example.messaging.core.mapping;

import com.example.messaging.core.model.SchemaCoordinates;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        this.byJavaType = mappings.stream()
                .collect(Collectors.toUnmodifiableMap(TypeMapping::javaType, Function.identity()));
        this.byCoordinates = mappings.stream()
                .collect(Collectors.toUnmodifiableMap(TypeMapping::coordinates, Function.identity()));
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

    /** Convenience: lookup by coordinates without version (latest). */
    public Optional<TypeMapping> findByGroupAndArtifact(String groupId, String artifactId) {
        return all.stream()
                .filter(m -> m.coordinates().groupId().equals(groupId)
                        && m.coordinates().artifactId().equals(artifactId))
                .findFirst();
    }
}
