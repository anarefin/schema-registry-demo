package com.example.schemagen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tolerant-reader invariant for generated JSON Schema: every object node must carry
 * {@code "additionalProperties": true} explicitly (never {@code false}).
 */
final class TolerantReaderSchemaInvariants {

    private TolerantReaderSchemaInvariants() {}

    /** Walk {@code schema} and set {@code additionalProperties: true} on every object node. */
    static void enforceAdditionalPropertiesTrue(ObjectNode schema) {
        if (schema == null) {
            return;
        }
        if (isObjectSchema(schema)) {
            schema.put("additionalProperties", true);
        }
        schema.fields().forEachRemaining(entry -> enforceOnNode(entry.getValue()));
    }

    private static void enforceOnNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            enforceAdditionalPropertiesTrue(objectNode);
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(TolerantReaderSchemaInvariants::enforceOnNode);
        }
    }

    static boolean isObjectSchema(ObjectNode node) {
        JsonNode type = node.get("type");
        return node.has("properties")
                || (type != null && type.isTextual() && "object".equals(type.textValue()));
    }
}
