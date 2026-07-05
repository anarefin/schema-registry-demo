package com.example.contracts.payments.codefirst;

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
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SchemaGenerator {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: SchemaGenerator <output-path>");
        }
        Path outputPath = Paths.get(args[0]);
        String schema = generateSchema();
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
        System.out.println("Schema written to " + outputPath.toAbsolutePath());
    }

    public static String generateSchema() throws IOException {
        SchemaGeneratorConfig config = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(Option.FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS)
                .with(Option.INLINE_ALL_SCHEMAS)
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .build();

        com.github.victools.jsonschema.generator.SchemaGenerator generator =
                new com.github.victools.jsonschema.generator.SchemaGenerator(config);

        ObjectNode schema = (ObjectNode) generator.generateSchema(PaymentProcessedEvent.class);

        // Inject stable $id and title derived from the class name only (no timestamps/env values)
        String typeName = PaymentProcessedEvent.class.getSimpleName();
        schema.put("$id", typeName);
        schema.put("title", typeName);

        // Recursively sort all ObjectNode keys so output is byte-stable regardless of
        // victools' or Jackson's internal insertion order.
        ObjectMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();

        JsonNode sorted = sortedNode(schema, mapper);

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
                .withObjectIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        return mapper.writer(printer).writeValueAsString(sorted) + "\n";
    }

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
