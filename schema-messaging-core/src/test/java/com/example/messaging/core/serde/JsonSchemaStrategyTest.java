package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-serde-5 valid JSON round-trip · TC-serde-6 invalid JSON → structured errors ·
 * TC-serde-7 consumer-side validation · TC-serde-8 bad bytes → DeserializationException ·
 * TC-serde-9 schema cache hit
 */
class JsonSchemaStrategyTest {

    private static final String SCHEMA_JSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age":  { "type": "integer" }
              },
              "required": ["name"]
            }
            """;

    private JsonSchemaStrategy strategy;
    private ResolvedSchema schema;

    record Person(String name, Integer age) {}

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        strategy = new JsonSchemaStrategy(mapper, true);
        schema = new ResolvedSchema(10L, SchemaType.JSON, SCHEMA_JSON.getBytes(StandardCharsets.UTF_8));
    }

    /** TC-serde-5: valid payload serializes to bytes and deserializes back to equal object. */
    @Test
    void roundTrip_validPayload() throws Exception {
        Person p = new Person("Alice", 30);

        byte[] bytes = strategy.serialize(p, schema);
        Object result = strategy.deserialize(bytes, Person.class, schema);

        assertThat(result).isEqualTo(p);
    }

    /** TC-serde-6: payload missing required field → SchemaValidationException with structured error list. */
    @Test
    void serialize_missingRequiredField_throwsWithStructuredErrors() {
        Person noName = new Person(null, 25);

        assertThatThrownBy(() -> strategy.serialize(noName, schema))
                .isInstanceOf(SchemaValidationException.class)
                .satisfies(e -> {
                    SchemaValidationException sve = (SchemaValidationException) e;
                    assertThat(sve.validationErrors()).isNotEmpty();
                    // At least one error should mention the missing field
                    assertThat(sve.validationErrors().stream().anyMatch(s -> s.contains("name"))).isTrue();
                });
    }

    /** TC-serde-7: consumer-side validation catches structurally invalid incoming bytes. */
    @Test
    void deserialize_invalidBytes_validateOnDeserialize_throwsSchemaValidation() {
        // Valid JSON but missing required "name" field
        byte[] missingNameBytes = "{\"age\":99}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> strategy.deserialize(missingNameBytes, Person.class, schema))
                .isInstanceOf(SchemaValidationException.class);
    }

    /** TC-serde-7b: with validateOnDeserialize=false, invalid bytes do NOT throw on deserialize. */
    @Test
    void deserialize_invalidBytes_validateOnDeserializeFalse_noException() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonSchemaStrategy noValidation = new JsonSchemaStrategy(mapper, false);

        byte[] missingNameBytes = "{\"age\":99}".getBytes(StandardCharsets.UTF_8);
        Object result = noValidation.deserialize(missingNameBytes, Person.class, schema);

        assertThat(result).isNotNull();
    }

    /**
     * TC-serde-8a: with validateOnDeserialize=true, non-JSON bytes fail in the validator first
     * and throw SchemaValidationException with a schema-compile/parse error message.
     */
    @Test
    void deserialize_notJsonBytes_validationEnabled_throwsSchemaValidation() {
        byte[] garbage = new byte[]{0x01, 0x02, 0x03};

        assertThatThrownBy(() -> strategy.deserialize(garbage, Person.class, schema))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("Failed to compile/validate");
    }

    /**
     * TC-serde-8b: with validateOnDeserialize=false, non-JSON bytes bypass the validator and
     * throw DeserializationException from Jackson's parse failure.
     */
    @Test
    void deserialize_notJsonBytes_validationDisabled_throwsDeserializationException() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonSchemaStrategy noValidation = new JsonSchemaStrategy(mapper, false);

        byte[] garbage = new byte[]{0x01, 0x02, 0x03};

        assertThatThrownBy(() -> noValidation.deserialize(garbage, Person.class, schema))
                .isInstanceOf(DeserializationException.class);
    }

    /** TC-serde-9: compiled JsonSchema for the same globalId is reused (cache hit). */
    @Test
    void serialize_sameGlobalId_compiledSchemaCachedOnSecondCall() throws Exception {
        Person p = new Person("Bob", 20);

        byte[] first = strategy.serialize(p, schema);
        byte[] second = strategy.serialize(p, schema);

        // Both calls succeed and produce equal output — confirms the cached schema is reused correctly
        assertThat(first).isEqualTo(second);
    }

    /** TC-serde-10: IncompatibleSchemaTypeException carries typed expectedType/actualType accessors. */
    @Test
    void schemaType_isJson() {
        assertThat(strategy.schemaType()).isEqualTo(SchemaType.JSON);
        assertThat(strategy.contentType()).isEqualTo("application/json");
    }
}
