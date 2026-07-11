package com.example.messaging.core.converter;

import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.messaging.core.schema.LocalSchemaCatalog;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

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
                "test.fixture");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));
        LocalSchemaCatalog catalog = new LocalSchemaCatalog(registry);
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        converter = new SchemaAwareMessageConverter(
                registry, catalog, List.of(new JsonSchemaStrategy(mapper)));
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
}
