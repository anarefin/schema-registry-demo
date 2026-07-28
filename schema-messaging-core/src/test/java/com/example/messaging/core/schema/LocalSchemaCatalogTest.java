package com.example.messaging.core.schema;

import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSchemaCatalogTest {

    private static final SchemaCoordinates FIXTURE_COORDS = new SchemaCoordinates("test.fixtures", "FixtureEvent");

    @Test
    void loadsAndReturnsSchemaPresentOnClasspath() {
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(
                new TypeMapping(FixtureEvent.class, FIXTURE_COORDS, SchemaType.JSON, "fixtures.event", "test.fixtures.exchange")));

        LocalSchemaCatalog catalog = new LocalSchemaCatalog(registry);

        ResolvedSchema schema = catalog.get(FIXTURE_COORDS);
        assertThat(schema.getCoordinates()).isEqualTo(FIXTURE_COORDS);
        assertThat(schema.getSchemaType()).isEqualTo(SchemaType.JSON);
        assertThat(new String(schema.getRawContent())).contains("\"type\": \"object\"");
    }

    @Test
    void constructorFailsFastWhenClasspathResourceMissing() {
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(
                new TypeMapping(MissingEvent.class, new SchemaCoordinates("test.fixtures", "MissingEvent"),
                        SchemaType.JSON, "fixtures.missing", "test.fixtures.exchange")));

        assertThatThrownBy(() -> new LocalSchemaCatalog(registry))
                .isInstanceOf(SchemaNotFoundException.class)
                .hasMessageContaining("missing-event.schema.json");
    }

    private static final class FixtureEvent {}

    private static final class MissingEvent {}
}
