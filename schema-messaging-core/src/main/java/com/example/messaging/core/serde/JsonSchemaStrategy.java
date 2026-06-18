package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON Schema serde (spec §6): the sole wire format in this minimal POC.
 * Validates payload via networknt json-schema-validator (Draft 2020-12),
 * then serializes / deserializes with Jackson.
 *
 * <p>Compiled {@link JsonSchema} objects are cached by globalId — parsing the schema
 * content string on every call is expensive and the result is always identical for the
 * same globalId.
 *
 * <p>Consumer-side validation ({@code validateOnDeserialize}) is enabled by default so
 * that structurally invalid inbound JSON (wrong field types, missing required fields from
 * a mis-deployed producer) is caught and routed to DLQ rather than silently deserializing
 * into a partial POJO.
 */
public class JsonSchemaStrategy {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaStrategy.class);

    /** A globalId maps to immutable schema content, so a small bounded cache suffices. */
    private static final int COMPILED_SCHEMA_CACHE_MAX = 200;

    private final ObjectMapper objectMapper;
    private final boolean validateOnDeserialize;

    // Compiled JsonSchema objects are immutable and thread-safe; cache by globalId (bounded, no
    // TTL — the globalId→schema mapping never changes).
    private final Cache<Long, JsonSchema> compiledSchemaCache = Caffeine.newBuilder()
            .maximumSize(COMPILED_SCHEMA_CACHE_MAX)
            .build();

    public JsonSchemaStrategy(ObjectMapper objectMapper, boolean validateOnDeserialize) {
        this.objectMapper = objectMapper;
        this.validateOnDeserialize = validateOnDeserialize;
    }

    public JsonSchemaStrategy(ObjectMapper objectMapper) {
        this(objectMapper, true);
    }

    public SchemaType schemaType() {
        return SchemaType.JSON;
    }

    /** MIME content-type for the wire format, used to populate the AMQP content-type header. */
    public String contentType() {
        return SchemaType.JSON.contentType();
    }

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

    // ---- private -----------------------------------------------------------

    private void validate(byte[] jsonBytes, ResolvedSchema resolvedSchema) throws SchemaValidationException {
        String coordinatesCtx = "globalId=" + resolvedSchema.globalId();
        try {
            JsonSchema jsonSchema = compiledSchemaCache.get(resolvedSchema.globalId(), id -> {
                JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
                return factory.getSchema(new String(resolvedSchema.rawContent(), StandardCharsets.UTF_8));
            });
            JsonNode node = objectMapper.readTree(jsonBytes);
            Set<ValidationMessage> errors = jsonSchema.validate(node);
            if (!errors.isEmpty()) {
                List<String> errorMessages = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .limit(5)
                        .collect(Collectors.toList());
                throw new SchemaValidationException(coordinatesCtx, errorMessages);
            }
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaValidationException(coordinatesCtx,
                    "Failed to compile/validate JSON schema: " + e.getMessage(), e);
        }
    }
}
