package com.example.schemagen;

import java.beans.Introspector;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces the paired-annotation contract at {@code process-classes} and produces the validated,
 * effective event-mapping metadata. Every {@code @GenerateSchema}
 * type discovered by {@link GenerateSchemaScanner} must:
 *
 * <ul>
 *   <li>be a top-level, public {@code record} (no nested/local/anonymous/abstract/interface/enum);
 *   <li>carry a paired {@code @EventMapping} with non-blank {@code groupId}/{@code exchange}/
 *       {@code routingKey};
 *   <li>have distinct effective schema coordinates {@code (groupId, artifactId)} and a distinct
 *       deterministic bean name across the whole module.
 * </ul>
 *
 * <p>Any violation aborts the build with an {@link IllegalStateException}. The effective
 * {@code artifactId} defaults to the simple class name (matching {@code Mappings.json}), and the
 * bean name is computed identically to {@code MappingBeanNames} in {@code event-contract-kit}
 * ({@link Introspector#decapitalize(String)} + {@code "Mapping"}) so the same name is produced at
 * build time and at runtime.
 */
public final class EventMappingValidator {

    private EventMappingValidator() {}

    /**
     * @param generateSchemaTypes {@code @GenerateSchema} types from {@link GenerateSchemaScanner}
     * @return validated metadata, sorted by FQCN
     * @throws IllegalStateException on any pairing, shape, blank-attribute, or duplicate violation
     */
    public static List<EventMappingMetadata> validate(List<Class<?>> generateSchemaTypes) {
        List<EventMappingMetadata> metadata = new ArrayList<>();
        Map<String, String> seenTypes = new HashMap<>();        // fqcn -> fqcn
        Map<String, String> seenCoordinates = new HashMap<>();  // "group/artifact" -> fqcn
        Map<String, String> seenBeanNames = new HashMap<>();    // beanName -> fqcn

        for (Class<?> type : generateSchemaTypes) {
            String fqcn = type.getName();

            if (seenTypes.putIfAbsent(fqcn, fqcn) != null) {
                throw new IllegalStateException("Duplicate @GenerateSchema type: " + fqcn);
            }

            requireSupportedShape(type, fqcn);

            EventMappingReader.RawAttributes attrs = EventMappingReader.read(type);
            if (attrs == null) {
                throw new IllegalStateException(
                        "@GenerateSchema type " + fqcn + " is missing a paired @EventMapping");
            }
            requireNonBlank(attrs.groupId(), "groupId", fqcn);
            requireNonBlank(attrs.exchange(), "exchange", fqcn);
            requireNonBlank(attrs.routingKey(), "routingKey", fqcn);

            String artifactId = attrs.artifactId().isBlank()
                    ? type.getSimpleName()
                    : attrs.artifactId();
            String beanName = Introspector.decapitalize(type.getSimpleName()) + "Mapping";

            String coordinateKey = attrs.groupId() + "/" + artifactId;
            String coordinateOwner = seenCoordinates.putIfAbsent(coordinateKey, fqcn);
            if (coordinateOwner != null) {
                throw new IllegalStateException(
                        "Duplicate effective schema coordinates (" + attrs.groupId() + ", "
                                + artifactId + ") on " + fqcn + " and " + coordinateOwner);
            }

            String beanNameOwner = seenBeanNames.putIfAbsent(beanName, fqcn);
            if (beanNameOwner != null) {
                throw new IllegalStateException(
                        "Duplicate mapping bean name '" + beanName + "' on " + fqcn
                                + " and " + beanNameOwner);
            }

            metadata.add(new EventMappingMetadata(type, fqcn, attrs.groupId(), artifactId, beanName));
        }

        metadata.sort(Comparator.comparing(EventMappingMetadata::fqcn));
        return List.copyOf(metadata);
    }

    private static void requireSupportedShape(Class<?> type, String fqcn) {
        if (type.isMemberClass() || type.isLocalClass() || type.isAnonymousClass()) {
            throw new IllegalStateException("@GenerateSchema type " + fqcn
                    + " must be a top-level type (no nested, local, or anonymous event records)");
        }
        if (!Modifier.isPublic(type.getModifiers())) {
            throw new IllegalStateException("@GenerateSchema type " + fqcn + " must be public");
        }
        if (type.isInterface()) {
            throw new IllegalStateException(
                    "@GenerateSchema type " + fqcn + " must be a record, not an interface");
        }
        if (type.isEnum()) {
            throw new IllegalStateException(
                    "@GenerateSchema type " + fqcn + " must be a record, not an enum");
        }
        if (!type.isRecord()) {
            throw new IllegalStateException("@GenerateSchema type " + fqcn
                    + " must be a record (abstract and plain classes are not supported)");
        }
    }

    private static void requireNonBlank(String value, String attribute, String fqcn) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "@EventMapping." + attribute + " on " + fqcn + " must be non-blank");
        }
    }
}
