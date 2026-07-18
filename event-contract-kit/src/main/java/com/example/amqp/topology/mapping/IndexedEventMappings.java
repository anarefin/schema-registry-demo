package com.example.amqp.topology.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The validated, merged runtime view of the build-time event index. Loads every FQCN reported by
 * {@link EventMappingIndexReader} (strictly from the index — never a classpath package scan), reads
 * each type's {@link EventMapping} reflectively, and turns it into a {@link TypeMapping} via
 * {@link Mappings} (the single construction path, so annotation-driven mappings are byte-identical
 * to hand-written ones).
 *
 * <p>Integrity is enforced across the whole merged classpath before any bean is registered:
 * <ul>
 *   <li><b>annotation/index mismatch</b> — an indexed class that cannot be loaded, or no longer
 *       carries {@link EventMapping}, fails fast; the index must exactly track the annotations;</li>
 *   <li><b>duplicate coordinates</b> — two indexed events resolving to the same
 *       {@link SchemaCoordinates} (a schema-identity collision, possibly across jars) fails fast;</li>
 *   <li><b>bean-name collision</b> — two indexed events resolving to the same deterministic
 *       {@link MappingBeanNames} bean name (e.g. equal simple names in different domains) fails
 *       fast. The build-time check is per-module and cannot see this across jars; catching it here
 *       turns a silent "second registration is skipped as an override" into a startup error.</li>
 * </ul>
 * Filtering to a specific event package ({@link #inPackage(String)}) happens only <em>after</em>
 * this global validation, and uses exact package equality — never prefix matching.
 */
public final class IndexedEventMappings {

    /** One indexed event: its loaded Java type and the {@link TypeMapping} built from its annotation. */
    public record Entry(Class<?> javaType, TypeMapping mapping) {}

    private final List<Entry> entries;

    private IndexedEventMappings(List<Entry> entries) {
        this.entries = entries;
    }

    /** Loads, validates, and merges all indexed events visible to the class loader. */
    public static IndexedEventMappings load(ClassLoader classLoader) {
        List<String> fqcns = EventMappingIndexReader.readAll(classLoader);
        List<Entry> entries = new ArrayList<>(fqcns.size());
        Map<SchemaCoordinates, String> byCoordinates = new HashMap<>();
        Map<String, String> byBeanName = new HashMap<>();

        for (String fqcn : fqcns) {
            Class<?> type = load(fqcn, classLoader);
            EventMapping annotation = type.getAnnotation(EventMapping.class);
            if (annotation == null) {
                throw new EventMappingRegistrationException(
                        "Indexed type " + fqcn + " is not annotated with @EventMapping"
                                + " (annotation/index mismatch — the index is stale or hand-edited)");
            }

            TypeMapping mapping = toMapping(type, annotation);
            String coordinateOwner = byCoordinates.putIfAbsent(mapping.coordinates(), fqcn);
            if (coordinateOwner != null) {
                throw new EventMappingRegistrationException(
                        "Duplicate schema coordinates " + mapping.coordinates()
                                + " for " + fqcn + " (already claimed by " + coordinateOwner + ")");
            }

            String beanName = MappingBeanNames.forType(type);
            String beanNameOwner = byBeanName.putIfAbsent(beanName, fqcn);
            if (beanNameOwner != null) {
                throw new EventMappingRegistrationException(
                        "Duplicate bean name '" + beanName + "' for " + fqcn
                                + " (already claimed by " + beanNameOwner
                                + "; two indexed events share a Java simple name across domains)");
            }
            entries.add(new Entry(type, mapping));
        }
        return new IndexedEventMappings(entries);
    }

    /** All indexed events across the classpath, in index (lexical FQCN) order. */
    public List<Entry> all() {
        return List.copyOf(entries);
    }

    /** Indexed events whose Java type sits in exactly {@code packageName} (no prefix matching). */
    public List<Entry> inPackage(String packageName) {
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.javaType().getPackageName().equals(packageName)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    private static Class<?> load(String fqcn, ClassLoader classLoader) {
        try {
            // initialize=false: read annotations without running static initializers (mirrors the
            // build-time scanner's no-static-init discipline).
            return Class.forName(fqcn, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new EventMappingRegistrationException(
                    "Indexed type " + fqcn + " could not be loaded"
                            + " (annotation/index mismatch — the index references a missing class)", e);
        }
    }

    private static TypeMapping toMapping(Class<?> type, EventMapping annotation) {
        if (annotation.schemaType() != SchemaType.JSON) {
            throw new EventMappingRegistrationException(
                    "Unsupported @EventMapping.schemaType=" + annotation.schemaType()
                            + " on " + type.getName()
                            + "; only SchemaType.JSON is supported at runtime");
        }
        String artifactId = annotation.artifactId().isEmpty()
                ? type.getSimpleName()
                : annotation.artifactId();
        return Mappings.forDomain(annotation.groupId(), annotation.exchange())
                .json(type, artifactId, annotation.routingKey());
    }
}
