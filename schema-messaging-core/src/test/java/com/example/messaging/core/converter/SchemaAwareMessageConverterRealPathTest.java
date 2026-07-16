package com.example.messaging.core.converter;

import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.messaging.core.schema.LocalSchemaCatalog;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-path round-trip: {@link LocalSchemaCatalog} + {@link JsonSchemaStrategy} +
 * classpath fixture {@code schemas/fixture-event.schema.json} (no mocks).
 */
class SchemaAwareMessageConverterRealPathTest {

    /** Simple name → {@code fixture-event.schema.json} via {@code SchemaFileNaming}. */
    record FixtureEvent(String id) {}

    private SchemaAwareMessageConverter converter;

    @BeforeEach
    void setUp() {
        TypeMapping mapping = new TypeMapping(
                FixtureEvent.class,
                new SchemaCoordinates("events.test", "FixtureEvent"),
                SchemaType.JSON,
                "test.fixture",
                "events.test.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));
        LocalSchemaCatalog catalog = new LocalSchemaCatalog(registry);
        converter = new SchemaAwareMessageConverter(
                registry, catalog, List.of(new JsonSchemaStrategy()));
    }

    @Test
    void roundTrip_realCatalogAndStrategy() {
        FixtureEvent event = new FixtureEvent("evt-1");

        Message msg = converter.toMessage(event, new MessageProperties());
        Object result = converter.fromMessage(msg);

        assertThat(result).isEqualTo(event);
        MessageProperties props = msg.getMessageProperties();
        assertThat((Object) props.getHeader(SchemaMessageHeaders.GROUP_ID)).isEqualTo("events.test");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.ARTIFACT_ID)).isEqualTo("FixtureEvent");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.TYPE)).isEqualTo("JSON");
        assertThat(props.getContentType()).isEqualTo("application/json");
    }

    /**
     * Defends the tolerant-reader contract through the real, unmocked catalog + strategy +
     * converter chain: an unmapped field in the wire payload must not break consumption.
     */
    @Test
    void fromMessage_extraUnknownField_toleratedByRealConverter() {
        byte[] bodyWithExtraField =
                "{\"id\":\"evt-1\",\"extra\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.test");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "FixtureEvent");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        props.setContentType("application/json");

        Object result = converter.fromMessage(new Message(bodyWithExtraField, props));

        assertThat(result).isEqualTo(new FixtureEvent("evt-1"));
    }
}
