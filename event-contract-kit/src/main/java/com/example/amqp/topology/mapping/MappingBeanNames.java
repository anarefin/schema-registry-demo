package com.example.amqp.topology.mapping;

import java.beans.Introspector;

/**
 * Deterministic Spring bean names for indexed {@link TypeMapping} beans (spec §13, locked
 * algorithm). The name is {@code Introspector.decapitalize(simpleName) + "Mapping"} so
 * {@code OrderCreated} → {@code orderCreatedMapping} and {@code CustomerAddressAdded} →
 * {@code customerAddressAddedMapping}. A leading run of capitals is left untouched by
 * {@link Introspector#decapitalize(String)} (e.g. {@code URLEvent} → {@code URLEventMapping}) —
 * that JDK behaviour is deliberately relied on so the same name is computed at build time and at
 * runtime, and so application overrides can target it.
 *
 * <p>Framework-free: uses only {@code java.beans}, never a Spring naming utility.
 */
public final class MappingBeanNames {

    private MappingBeanNames() {}

    /** Bean name for the given event Java type, from its simple name. */
    public static String forType(Class<?> javaType) {
        return forSimpleName(javaType.getSimpleName());
    }

    /** Bean name for the given Java simple name. */
    public static String forSimpleName(String simpleName) {
        return Introspector.decapitalize(simpleName) + "Mapping";
    }
}
