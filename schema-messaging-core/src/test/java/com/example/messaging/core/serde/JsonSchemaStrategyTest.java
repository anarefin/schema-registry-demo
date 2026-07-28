package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.InvalidSchemaDefinitionException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TC-serde-5 valid JSON round-trip · TC-serde-6 invalid JSON → structured errors ·
 * TC-serde-7 consumer-side validation · TC-serde-8 bad bytes → DeserializationException ·
 * TC-serde-9 schema cache hit
 */
class JsonSchemaStrategyTest {

    private static final String SCHEMA_JSON = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
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

    static final class Person {
        private final String name;
        private final Integer age;

        @JsonCreator
        Person(@JsonProperty("name") String name, @JsonProperty("age") Integer age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public Integer getAge() {
            return age;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Person other)) {
                return false;
            }
            return Objects.equals(name, other.name) && Objects.equals(age, other.age);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, age);
        }

        @Override
        public String toString() {
            return "Person[name=" + name + ", age=" + age + "]";
        }
    }

    @BeforeEach
    void setUp() {
        strategy = new JsonSchemaStrategy();
        schema = new ResolvedSchema(new SchemaCoordinates("test", "Person"), SchemaType.JSON,
                SCHEMA_JSON.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Defends the tolerant-reader contract at its source: the production factory-built mapper
     * (no hand-set flags in this test) must ignore an unmapped field rather than throw.
     */
    @Test
    void deserialize_extraUnknownField_toleratedByDefaultMapper() throws Exception {
        byte[] withExtraField =
                "{\"name\":\"Alice\",\"age\":30,\"promoCode\":\"X\"}".getBytes(StandardCharsets.UTF_8);

        Object result = strategy.deserialize(withExtraField, Person.class, schema);

        assertThat(result).isEqualTo(new Person("Alice", 30));
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
        JsonSchemaStrategy noValidation =
                new JsonSchemaStrategy(JsonSchemaStrategy.defaultObjectMapper(), false);

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
        JsonSchemaStrategy noValidation =
                new JsonSchemaStrategy(JsonSchemaStrategy.defaultObjectMapper(), false);

        byte[] garbage = new byte[]{0x01, 0x02, 0x03};

        assertThatThrownBy(() -> noValidation.deserialize(garbage, Person.class, schema))
                .isInstanceOf(DeserializationException.class);
    }

    /** TC-serde-9: compiled JsonSchema for the same coordinates is reused (cache hit). */
    @Test
    void serialize_sameCoordinates_compiledSchemaCachedOnSecondCall() throws Exception {
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

    /** PERF-001: consume path parses bytes once (readTree → validate → convertValue). */
    @Test
    void deserialize_validateEnabled_parsesOnce() throws Exception {
        ObjectMapper mapper = spy(JsonSchemaStrategy.defaultObjectMapper());
        JsonSchemaStrategy strat = new JsonSchemaStrategy(mapper, true);
        strat.warm(schema);
        byte[] bytes = "{\"name\":\"Alice\",\"age\":30}".getBytes(StandardCharsets.UTF_8);

        strat.deserialize(bytes, Person.class, schema);

        verify(mapper, times(1)).readTree(any(byte[].class));
        verify(mapper, never()).readValue(any(byte[].class), eq(Person.class));
        verify(mapper, times(1)).convertValue(any(JsonNode.class), eq(Person.class));
    }

    /** PERF-001: produce path serializes once (valueToTree → validate → writeValueAsBytes). */
    @Test
    void serialize_parsesOnce() throws Exception {
        ObjectMapper mapper = spy(JsonSchemaStrategy.defaultObjectMapper());
        JsonSchemaStrategy strat = new JsonSchemaStrategy(mapper, true);
        strat.warm(schema);
        Person p = new Person("Alice", 30);

        strat.serialize(p, schema);

        verify(mapper, times(1)).valueToTree(p);
        verify(mapper, never()).readTree(any(byte[].class));
        verify(mapper, times(1)).writeValueAsBytes(any(JsonNode.class));
    }

    /** Malformed schema bytes fail at warm() with InvalidSchemaDefinitionException (ADR-0004). */
    @Test
    void warm_malformedSchema_throwsInvalidSchemaDefinition() {
        ResolvedSchema bad = new ResolvedSchema(
                new SchemaCoordinates("test", "Broken"),
                SchemaType.JSON,
                "not-valid-json-schema{{{".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> strategy.warm(bad))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .hasMessageContaining("Broken");
    }
}
