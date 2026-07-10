package com.example.messaging.core.schema;

import java.util.Locale;

/**
 * Converts an event record's simple class name into its classpath schema resource filename, e.g.
 * {@code OrderCreated} -&gt; {@code order-created.schema.json} (looked up under
 * {@code schemas/} on the classpath by {@link LocalSchemaCatalog}).
 *
 * <p>This algorithm must stay byte-identical to
 * {@code com.example.schemagen.SchemaFileNaming} in {@code schema-gen-tools} — that copy derives
 * the file this module's classes read at runtime, generated at build time. See the parity tests in
 * both modules and {@code docs/adr/0004-local-schema-validation.md}. The two copies are
 * intentionally NOT merged into a shared module: {@code schema-gen-tools} and
 * {@code schema-messaging-core} must remain dependency-free of each other.
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
