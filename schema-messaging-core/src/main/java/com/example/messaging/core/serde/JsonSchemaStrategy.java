package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.InvalidSchemaDefinitionException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 *
 * <p>Each message is parsed once: produce uses {@code valueToTree} → validate →
 * {@code writeValueAsBytes}; consume uses {@code readTree} → validate → {@code convertValue}.
 *
 * <p>Owns a dedicated tolerant {@link ObjectMapper} it builds for itself — never a bean, never
 * accepted from the application context. A service is free to register its own strict
 * {@code @Bean ObjectMapper} (e.g. {@code FAIL_ON_UNKNOWN_PROPERTIES=true} for its REST layer)
 * without that choice silently breaking FORWARD-compatible schema evolution on the messaging
 * path (adding an optional field to an event must stay a no-op for existing consumers).
 */
public class JsonSchemaStrategy implements SerializationStrategy {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaStrategy.class);

    private final ObjectMapper objectMapper;
    private final boolean validateOnDeserialize;

    // Compiled JsonSchema objects are immutable and thread-safe; cache by coordinates.
    private final Map<SchemaCoordinates, JsonSchema> compiledSchemaCache = new ConcurrentHashMap<>();

    public JsonSchemaStrategy() {
        this(defaultObjectMapper(), true);
    }

    JsonSchemaStrategy(ObjectMapper objectMapper, boolean validateOnDeserialize) {
        this.objectMapper = objectMapper;
        this.validateOnDeserialize = validateOnDeserialize;
    }

    /**
     * Builds the tolerant mapper messaging deserialization uses: unknown JSON properties are
     * ignored (spec §01 — forward-compatible event evolution), dates serialize as ISO-8601
     * strings (matching the generated schemas' {@code "format":"date-time"}), and null optionals
     * are omitted so they don't fail a field's {@code type} check when absent.
     *
     * <p>Also the baseline {@code SchemaMessagingAutoConfiguration.objectMapper()} fallback bean
     * builds on: sharing this builder avoids hand-copying the same config twice, without
     * reintroducing the coupling this class exists to avoid — each caller still gets its own,
     * independently constructed {@link ObjectMapper} instance (never a shared bean reference),
     * and remains free to layer further config on top for its own purposes.
     */
    public static ObjectMapper defaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    @Override
    public SchemaType schemaType() {
        return SchemaType.JSON;
    }

    @Override
    public byte[] serialize(Object payload, ResolvedSchema schema)
            throws SchemaValidationException, SerializationException {
        try {
            JsonNode node = objectMapper.valueToTree(payload);
            validate(node, schema);
            return objectMapper.writeValueAsBytes(node);
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
                JsonNode node = readTreeOrThrow(bytes, schema);
                validate(node, schema);
                return objectMapper.convertValue(node, targetType);
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
            throw new InvalidSchemaDefinitionException(schema.getCoordinates().toString(),
                    "Failed to compile JSON schema: " + e.getMessage(), e);
        }
    }

    // ---- private -----------------------------------------------------------

    private JsonNode readTreeOrThrow(byte[] bytes, ResolvedSchema schema) throws SchemaValidationException {
        try {
            return objectMapper.readTree(bytes);
        } catch (Exception e) {
            throw new SchemaValidationException(schema.getCoordinates().toString(),
                    "Failed to compile/validate JSON schema: " + e.getMessage(), e);
        }
    }

    private void validate(JsonNode node, ResolvedSchema resolvedSchema) throws SchemaValidationException {
        try {
            JsonSchema jsonSchema = compile(resolvedSchema);
            Set<ValidationMessage> errors = jsonSchema.validate(node);
            if (!errors.isEmpty()) {
                List<String> errorMessages = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .limit(5)
                        .collect(Collectors.toList());
                throw new SchemaValidationException(resolvedSchema.getCoordinates().toString(), errorMessages);
            }
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaValidationException(resolvedSchema.getCoordinates().toString(),
                    "Failed to compile/validate JSON schema: " + e.getMessage(), e);
        }
    }

    private JsonSchema compile(ResolvedSchema resolvedSchema) {
        return compiledSchemaCache.computeIfAbsent(resolvedSchema.getCoordinates(), coords -> {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(new String(resolvedSchema.getRawContent(), StandardCharsets.UTF_8));
        });
    }
}
