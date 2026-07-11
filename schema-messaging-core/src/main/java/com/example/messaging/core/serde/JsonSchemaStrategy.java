package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.InvalidSchemaDefinitionException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JSON Schema serialization strategy (spec §6).
 * Validates payload via networknt json-schema-validator (Draft-07 — matching the generated
 * schemas, which target Draft-07 so Apicurio 3.2.0's compatibility checker can gate them),
 * then serializes / deserializes with Jackson.
 *
 * <p>Compiled {@link JsonSchema} objects are cached by {@link SchemaCoordinates} — parsing the
 * schema content string on every call is expensive and the result is always identical for the
 * same coordinates. The set of coordinates is fixed and small (one per registered
 * {@code TypeMapping}), populated once via {@link #warm} during application startup, so a plain
 * {@link ConcurrentHashMap} is used rather than an eviction-aware cache.
 *
 * <p>Consumer-side validation ({@code validateOnDeserialize}) is enabled by default so
 * that structurally invalid inbound JSON (wrong field types, missing required fields from
 * a mis-deployed producer) is caught and routed to DLQ rather than silently deserializing
 * into a partial POJO.
 */
public class JsonSchemaStrategy implements SerializationStrategy {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaStrategy.class);

    private final ObjectMapper objectMapper;
    private final boolean validateOnDeserialize;

    // Compiled JsonSchema objects are immutable and thread-safe; cache by coordinates.
    private final Map<SchemaCoordinates, JsonSchema> compiledSchemaCache = new ConcurrentHashMap<>();

    public JsonSchemaStrategy(ObjectMapper objectMapper, boolean validateOnDeserialize) {
        this.objectMapper = objectMapper;
        this.validateOnDeserialize = validateOnDeserialize;
    }

    public JsonSchemaStrategy(ObjectMapper objectMapper) {
        this(objectMapper, true);
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
            if (validateOnDeserialize) {
                validate(bytes, schema);
            }
            return objectMapper.readValue(bytes, targetType);
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            log.debug("JSON deserialization failed for type={}", ctx, e);
            throw new DeserializationException(ctx, e);
        }
    }

    @Override
    public void warm(ResolvedSchema schema) {
        try {
            compile(schema);
        } catch (Exception e) {
            throw new InvalidSchemaDefinitionException(schema.coordinates().toString(),
                    "Failed to compile JSON schema: " + e.getMessage(), e);
        }
    }

    // ---- private -----------------------------------------------------------

    private void validate(byte[] jsonBytes, ResolvedSchema resolvedSchema) throws SchemaValidationException {
        try {
            JsonSchema jsonSchema = compile(resolvedSchema);
            JsonNode node = objectMapper.readTree(jsonBytes);
            Set<ValidationMessage> errors = jsonSchema.validate(node);
            if (!errors.isEmpty()) {
                List<String> errorMessages = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .limit(5)
                        .collect(Collectors.toList());
                throw new SchemaValidationException(resolvedSchema.coordinates().toString(), errorMessages);
            }
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaValidationException(resolvedSchema.coordinates().toString(),
                    "Failed to compile/validate JSON schema: " + e.getMessage(), e);
        }
    }

    private JsonSchema compile(ResolvedSchema resolvedSchema) {
        return compiledSchemaCache.computeIfAbsent(resolvedSchema.coordinates(), coords -> {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(new String(resolvedSchema.rawContent(), StandardCharsets.UTF_8));
        });
    }
}
