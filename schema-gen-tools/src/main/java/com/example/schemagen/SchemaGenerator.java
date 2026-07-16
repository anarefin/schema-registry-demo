package com.example.schemagen;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Domain-agnostic, byte-stable JSON Schema generator (spec: code-first). Given any annotated
 * event type (a Java record is the source of truth), produces a deterministic Draft-07
 * schema string suitable for committing next to the record and gating in CI.
 *
 * <p>Draft-07 (not 2020-12) is deliberate: Apicurio Registry 3.2.0's compatibility checker
 * (everit json-schema) only understands up to Draft-07 and returns HTTP 500
 * ("could not determine version") when asked to compare Draft 2020-12 schemas — which would
 * disable the FORWARD compatibility merge gate. Every keyword these records emit (format,
 * pattern, enum, numeric exclusiveMinimum, minLength/maxLength, allOf) is Draft-07-valid, so
 * semantics are unchanged.
 *
 * <p>Determinism is the whole game: the same class always yields byte-identical output regardless
 * of victools'/Jackson's internal ordering — enforced by recursive alphabetical key sorting, a
 * fixed pretty-printer, a stable {@code $id}/{@code title} derived from the simple class name (no
 * timestamps or environment values), and a trailing newline.
 *
 * <p>Enrichment sources: {@link JacksonModule} maps {@code @JsonPropertyDescription} to per-field
 * {@code description}; {@link JakartaValidationModule} maps {@code @NotNull} → {@code required},
 * {@code @Size} → {@code minLength}/{@code maxLength}, {@code @DecimalMin}/{@code @Min} →
 * {@code minimum}/{@code exclusiveMinimum}, and {@code @Pattern} → {@code pattern}.
 *
 * <p>Tolerant-reader invariant: every object node emits {@code "additionalProperties": true}
 * explicitly (not relying on JSON Schema's implicit default). See ADR-0009.
 */
public final class SchemaGenerator {

    private SchemaGenerator() {}

    /** Generate the byte-stable schema for {@code eventType}. */
    public static String generate(Class<?> eventType) {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
                // Record components are argument-free accessor methods — this is how their
                // properties (and their annotations) are discovered.
                .with(Option.FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS)
                .with(Option.INLINE_ALL_SCHEMAS)
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS));
        // Make tolerant-reader intent visible in committed JSON (ADR-0009 / schema-versioning 1c).
        // victools' AttributeCollector deliberately omits additionalProperties when the resolved
        // value is Boolean.TRUE (treats it as the JSON Schema default), so a resolver alone cannot
        // emit the keyword — force it via type-attribute override after collection.
        configBuilder.forTypesInGeneral()
                .withTypeAttributeOverride((node, scope, context) -> {
                    String typeKeyword = context.getKeyword(SchemaKeyword.TAG_TYPE);
                    String objectType = context.getKeyword(SchemaKeyword.TAG_TYPE_OBJECT);
                    String propertiesKeyword = context.getKeyword(SchemaKeyword.TAG_PROPERTIES);
                    JsonNode type = node.get(typeKeyword);
                    boolean objectSchema = node.has(propertiesKeyword)
                            || (type != null && type.isTextual() && objectType.equals(type.textValue()));
                    if (objectSchema) {
                        node.put(context.getKeyword(SchemaKeyword.TAG_ADDITIONAL_PROPERTIES), true);
                    }
                });
        SchemaGeneratorConfig config = configBuilder.build();

        com.github.victools.jsonschema.generator.SchemaGenerator generator =
                new com.github.victools.jsonschema.generator.SchemaGenerator(config);

        ObjectNode schema = (ObjectNode) generator.generateSchema(eventType);

        // Stable identity derived from the class name only (no timestamps/env values).
        // $id must be a valid absolute URI (a bare class name is rejected by strict validators
        // such as networknt), so it is expressed as a deterministic URN.
        String typeName = eventType.getSimpleName();
        schema.put("$id", "urn:example:schema:" + typeName);
        schema.put("title", typeName);

        ObjectMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();

        JsonNode sorted = sortedNode(schema, mapper);

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
                .withObjectIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        try {
            return mapper.writer(printer).writeValueAsString(sorted) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize schema for " + typeName, e);
        }
    }

    // Deep copy of node with every ObjectNode's keys sorted alphabetically.
    private static JsonNode sortedNode(JsonNode node, ObjectMapper mapper) {
        if (!node.isObject()) {
            return node;
        }
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        ObjectNode sorted = mapper.createObjectNode();
        for (String key : keys) {
            sorted.set(key, sortedNode(node.get(key), mapper));
        }
        return sorted;
    }
}
