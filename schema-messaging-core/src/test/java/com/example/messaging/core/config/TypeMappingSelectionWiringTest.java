package com.example.messaging.core.config;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.BitsEventHandler;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.schema.LocalSchemaCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves {@link SchemaMessagingAutoConfiguration} scopes {@link TypeMappingRegistry} /
 * {@link LocalSchemaCatalog} to handled types (and optional include/exclude).
 */
class TypeMappingSelectionWiringTest {

    private static final TypeMapping FIXTURE = new TypeMapping(
            FixtureEvent.class,
            new SchemaCoordinates("test.fixtures", "FixtureEvent"),
            SchemaType.JSON,
            "fixtures.event",
            "test.fixtures.exchange");

    private static final TypeMapping OTHER = new TypeMapping(
            OtherEvent.class,
            new SchemaCoordinates("test.fixtures", "OtherEvent"),
            SchemaType.JSON,
            "fixtures.other",
            "test.fixtures.exchange");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchemaMessagingAutoConfiguration.class))
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withBean("fixtureMapping", TypeMapping.class, () -> FIXTURE)
            .withBean("otherMapping", TypeMapping.class, () -> OTHER);

    @Test
    void consumerWithOneHandler_warmsOnlyHandledMapping() {
        runner.withBean("handler", FixtureHandler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TypeMappingRegistry registry = context.getBean(TypeMappingRegistry.class);
                    assertThat(registry.all()).containsExactly(FIXTURE);
                    assertThat(registry.findByJavaType(OtherEvent.class)).isEmpty();
                    LocalSchemaCatalog catalog = context.getBean(LocalSchemaCatalog.class);
                    assertThat(catalog.get(FIXTURE.getCoordinates()).getCoordinates()).isEqualTo(FIXTURE.getCoordinates());
                });
    }

    @Test
    void pureProducer_keepsAllClasspathMappings() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TypeMappingRegistry.class).all())
                    .containsExactlyInAnyOrder(FIXTURE, OTHER);
        });
    }

    @Test
    void includeProperty_limitsProducerMappings() {
        runner.withPropertyValues("events.mappings.include=OtherEvent")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TypeMappingRegistry.class).all())
                            .containsExactly(OTHER);
                });
    }

    /** Matches {@code schemas/fixture-event.schema.json}. */
    static final class FixtureEvent {
        private FixtureEvent() {}
    }

    /** Matches {@code schemas/other-event.schema.json}. */
    static final class OtherEvent {
        private OtherEvent() {}
    }

    static class FixtureHandler {
        @BitsEventHandler
        public void onFixture(FixtureEvent event) {
            // no-op
        }
    }
}
