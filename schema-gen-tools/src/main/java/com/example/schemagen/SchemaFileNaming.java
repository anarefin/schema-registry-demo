package com.example.schemagen;

import java.util.Locale;

/**
 * Converts an event class's simple name into the committed schema filename, e.g.
 * {@code OrderCreated} -&gt; {@code order-created.schema.json}.
 *
 * <p>This algorithm must stay byte-identical to
 * {@code com.example.messaging.core.schema.SchemaFileNaming} in {@code schema-messaging-core} —
 * one derives the file this module writes at build time, the other derives the classpath resource
 * path a running service reads at startup. See the parity tests in both modules and
 * {@code docs/adr/0004-local-schema-validation.md}. The two copies are intentionally NOT merged
 * into a shared module: {@code schema-gen-tools} and {@code schema-messaging-core} must remain
 * dependency-free of each other.
 */
public final class SchemaFileNaming {

    private SchemaFileNaming() {}

    public static String toFileName(String simpleClassName) {
        if (simpleClassName == null || simpleClassName.isBlank()) {
            throw new IllegalArgumentException("simpleClassName must be non-blank");
        }
        return simpleClassName.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT) + ".schema.json";
    }
}
