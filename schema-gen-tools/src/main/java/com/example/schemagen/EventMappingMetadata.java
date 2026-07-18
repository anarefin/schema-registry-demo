package com.example.schemagen;

/**
 * Validated, effective metadata for one code-first event record, produced by
 * {@link EventMappingValidator} and consumed by {@link EventMappingIndexWriter}.
 *
 * @param javaType   the event record type
 * @param fqcn       its fully-qualified class name (the index line)
 * @param groupId    the Apicurio group id from {@code @EventMapping}
 * @param artifactId the <em>effective</em> artifact id — the annotation value, or the simple class
 *                   name when the annotation left it blank
 * @param beanName   the deterministic Spring bean name the runtime registrar will use
 */
public record EventMappingMetadata(
        Class<?> javaType,
        String fqcn,
        String groupId,
        String artifactId,
        String beanName) {
}
