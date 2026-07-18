package com.example.messaging.core.config;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.publisher.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A decoy {@link MessageConverter} must not displace {@link SchemaAwareMessageConverter} —
 * otherwise {@link EventPublisher} could publish without schema validation / X-Schema-* headers.
 */
class SchemaConverterWiringTest {

    private static final TypeMapping FIXTURE = new TypeMapping(
            FixtureEvent.class,
            new SchemaCoordinates("test.fixtures", "FixtureEvent"),
            SchemaType.JSON,
            "fixtures.event",
            "test.fixtures.exchange");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchemaMessagingAutoConfiguration.class))
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withBean("fixtureMapping", TypeMapping.class, () -> FIXTURE);

    @Test
    void decoyMessageConverter_doesNotDisplaceSchemaAwareConverter() {
        runner.withBean("decoyConverter", MessageConverter.class, DecoyMessageConverter::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SchemaAwareMessageConverter.class);
                    assertThat(context.getBeansOfType(MessageConverter.class))
                            .containsKeys("schemaAwareMessageConverter", "decoyConverter");
                    assertThat(context.getBean(EventPublisher.class)).isNotNull();
                });
    }

    /** Matches {@code schemas/fixture-event.schema.json}. */
    static final class FixtureEvent {
        private FixtureEvent() {}
    }

    static final class DecoyMessageConverter implements MessageConverter {
        @Override
        public Message toMessage(Object object, MessageProperties messageProperties) {
            return new Message(new byte[0], messageProperties);
        }

        @Override
        public Object fromMessage(Message message) {
            return new Object();
        }
    }
}
