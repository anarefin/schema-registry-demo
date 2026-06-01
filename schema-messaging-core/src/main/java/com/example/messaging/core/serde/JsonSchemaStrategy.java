package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * JSON Schema serialization strategy (spec §6).
 * Validates payload via networknt json-schema-validator (Draft 2020-12),
 * then serializes / deserializes with Jackson.
 */
public class JsonSchemaStrategy implements SerializationStrategy {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaStrategy.class);

    private final ObjectMapper objectMapper;

    public JsonSchemaStrategy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SchemaType schemaType() {
        return SchemaType.JSON;
    }

    @Override
    public byte[] serialize(Object payload, ResolvedSchema schema)
            throws SchemaValidationException, SerializationException {
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            validate(jsonBytes, schema);
            return jsonBytes;
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SerializationException(payload.getClass().getName(), e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, Class<?> targetType, ResolvedSchema schema)
            throws DeserializationException {
        String ctx = targetType.getSimpleName();
        try {
            return objectMapper.readValue(bytes, targetType);
        } catch (Exception e) {
            log.debug("JSON deserialization failed for type={}", ctx, e);
            throw new DeserializationException(ctx, e);
        }
    }

    // ---- private -----------------------------------------------------------

    private void validate(byte[] jsonBytes, ResolvedSchema resolvedSchema) throws SchemaValidationException {
        String schemaContent = new String(resolvedSchema.rawContent(), StandardCharsets.UTF_8);
        String coordinatesCtx = "globalId=" + resolvedSchema.globalId();
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            JsonSchema jsonSchema = factory.getSchema(schemaContent);
            JsonNode node = objectMapper.readTree(jsonBytes);
            Set<ValidationMessage> errors = jsonSchema.validate(node);
            if (!errors.isEmpty()) {
                String detail = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .limit(5)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("validation error");
                throw new SchemaValidationException(coordinatesCtx, detail);
            }
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaValidationException(coordinatesCtx, "Failed to compile/validate JSON schema: " + e.getMessage(), e);
        }
    }
}
