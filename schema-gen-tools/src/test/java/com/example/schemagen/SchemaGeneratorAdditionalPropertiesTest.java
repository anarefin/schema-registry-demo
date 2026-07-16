package com.example.schemagen;

import com.example.schemagen.fixtures.EventWithNestedObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tolerant-reader invariant: every object node in a generated schema must carry
 * {@code "additionalProperties": true} (never {@code false}).
 */
class SchemaGeneratorAdditionalPropertiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generatedSchema_allowsAdditionalPropertiesOnEveryObjectNode() throws Exception {
        JsonNode root = MAPPER.readTree(SchemaGenerator.generate(EventWithNestedObject.class));

        assertNoAdditionalPropertiesFalse(root);
        assertObjectNodesCarryAdditionalPropertiesTrue(root);
    }

    /** Recurse every node; fail if any {@code additionalProperties} is literally false. */
    static void assertNoAdditionalPropertiesFalse(JsonNode node) {
        if (node.isObject()) {
            JsonNode ap = node.get("additionalProperties");
            assertThat(ap == null || !ap.isBoolean() || ap.booleanValue())
                    .as("additionalProperties must not be false at %s", node)
                    .isTrue();
            node.fields().forEachRemaining(e -> assertNoAdditionalPropertiesFalse(e.getValue()));
        } else if (node.isArray()) {
            node.forEach(SchemaGeneratorAdditionalPropertiesTest::assertNoAdditionalPropertiesFalse);
        }
    }

    /** Object schema nodes (type=object or properties present) must emit the keyword as true. */
    static void assertObjectNodesCarryAdditionalPropertiesTrue(JsonNode node) {
        if (node.isObject()) {
            if (isObjectSchema(node)) {
                JsonNode ap = node.get("additionalProperties");
                assertThat(ap)
                        .as("object schema must emit additionalProperties: true at %s", node)
                        .isNotNull();
                assertThat(ap.isBoolean() && ap.booleanValue())
                        .as("additionalProperties must be true at %s", node)
                        .isTrue();
            }
            node.fields().forEachRemaining(
                    e -> assertObjectNodesCarryAdditionalPropertiesTrue(e.getValue()));
        } else if (node.isArray()) {
            node.forEach(
                    SchemaGeneratorAdditionalPropertiesTest::assertObjectNodesCarryAdditionalPropertiesTrue);
        }
    }

    private static boolean isObjectSchema(JsonNode node) {
        JsonNode type = node.get("type");
        return (type != null && type.isTextual() && "object".equals(type.textValue()))
                || node.has("properties");
    }
}
