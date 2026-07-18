package com.example.messaging.core.schema;

import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaCoordinates;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eagerly loads every registered {@link TypeMapping}'s JSON Schema from the classpath at
 * construction time (see {@code docs/adr/0004-local-schema-validation.md}).
 *
 * <p>Schemas are generated at build time by {@code schema-gen-tools} and committed into each
 * {@code *-contracts} module's {@code src/main/resources/schemas/} directory, which lands on the
 * classpath of any service depending on that module. Only mappings present in the
 * {@link TypeMappingRegistry} are warmed — on consumers that is typically the
 * {@code @BitsEventHandler}-scoped subset (see {@code TypeMappingSelection}).
 *
 * <p>Fails fast: a missing classpath resource throws {@link SchemaNotFoundException} synchronously
 * out of the constructor, aborting Spring context refresh rather than surfacing on the first
 * message of that type. Malformed schema <em>content</em> is not checked here (this class stays
 * format-agnostic) — see {@link com.example.messaging.core.serde.SerializationStrategy#warm}.
 */
public class LocalSchemaCatalog {

    private final Map<SchemaCoordinates, ResolvedSchema> schemas;

    public LocalSchemaCatalog(TypeMappingRegistry typeMappingRegistry) {
        this(typeMappingRegistry, LocalSchemaCatalog.class.getClassLoader());
    }

    LocalSchemaCatalog(TypeMappingRegistry typeMappingRegistry, ClassLoader classLoader) {
        Map<SchemaCoordinates, ResolvedSchema> loaded = new LinkedHashMap<>();
        for (TypeMapping mapping : typeMappingRegistry.all()) {
            String resourcePath = "schemas/" + SchemaFileNaming.toFileName(mapping.javaType().getSimpleName());
            byte[] bytes = readResourceOrFail(classLoader, resourcePath, mapping);
            // Defensive copy — callers must not mutate the catalog's cached content.
            loaded.put(mapping.coordinates(),
                    new ResolvedSchema(mapping.coordinates(), mapping.schemaType(), bytes.clone()));
        }
        this.schemas = Map.copyOf(loaded);
    }

    /** Returns the pre-loaded schema for {@code coordinates}. Always present for a valid mapping. */
    public ResolvedSchema get(SchemaCoordinates coordinates) {
        ResolvedSchema schema = schemas.get(coordinates);
        if (schema == null) {
            // Defensive only — cannot happen for coordinates sourced from the same
            // TypeMappingRegistry this catalog was built from.
            throw new IllegalStateException("No locally-loaded schema for " + coordinates);
        }
        return schema;
    }

    private static byte[] readResourceOrFail(ClassLoader classLoader, String resourcePath, TypeMapping mapping) {
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SchemaNotFoundException(
                        "classpath:" + resourcePath + " (for " + mapping.javaType().getName() + ")");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new SchemaNotFoundException("classpath:" + resourcePath, e);
        }
    }
}
