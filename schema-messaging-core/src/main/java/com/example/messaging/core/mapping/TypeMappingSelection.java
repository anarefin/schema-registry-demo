package com.example.messaging.core.mapping;

import com.example.amqp.topology.mapping.TypeMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects which classpath {@link TypeMapping} beans enter the runtime registry/catalog.
 *
 * <ol>
 *   <li>If {@code include} is non-empty → keep only matching mappings (producer fat-jar escape hatch).</li>
 *   <li>Else if handled Java types are non-empty → keep intersection with those types (consumer scope).</li>
 *   <li>Else keep all (pure producer with no include filter).</li>
 *   <li>Always apply {@code exclude} last.</li>
 * </ol>
 */
public final class TypeMappingSelection {

    private TypeMappingSelection() {}

    public static List<TypeMapping> select(
            Collection<TypeMapping> mappings,
            Set<Class<?>> handledJavaTypes,
            TypeMappingSelectionProperties properties) {
        List<String> include = properties.getInclude();
        List<String> exclude = properties.getExclude();

        List<TypeMapping> selected;
        if (!include.isEmpty()) {
            selected = mappings.stream().filter(m -> matchesAny(m, include)).toList();
        } else if (handledJavaTypes != null && !handledJavaTypes.isEmpty()) {
            selected = mappings.stream()
                    .filter(m -> handledJavaTypes.contains(m.javaType()))
                    .toList();
        } else {
            selected = List.copyOf(mappings);
        }

        if (!exclude.isEmpty()) {
            selected = selected.stream().filter(m -> !matchesAny(m, exclude)).toList();
        }
        return new ArrayList<>(selected);
    }

    static boolean matchesAny(TypeMapping mapping, Collection<String> tokens) {
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.equals(mapping.javaType().getSimpleName())
                    || trimmed.equals(mapping.javaType().getName())
                    || trimmed.equals(mapping.coordinates().toString())) {
                return true;
            }
        }
        return false;
    }

    /** Convenience for tests — empty properties. */
    public static TypeMappingSelectionProperties emptyProperties() {
        return new TypeMappingSelectionProperties();
    }

    public static Set<Class<?>> setOf(Class<?>... types) {
        return new LinkedHashSet<>(List.of(types));
    }
}
