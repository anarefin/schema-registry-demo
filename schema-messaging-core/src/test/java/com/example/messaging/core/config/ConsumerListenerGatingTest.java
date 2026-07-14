package com.example.messaging.core.config;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.BitsEventHandler;
import com.example.messaging.core.consumer.BitsEventHandlerRegistrar;
import com.example.messaging.core.consumer.DlxMessageRecoverer;
import com.example.messaging.core.consumer.DlxRoutingAdvice;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the producer/consumer boundary of {@link SchemaMessagingConsumerAutoConfiguration}
 * (spec 09 / ARCH-001): the listener stack is gated behind {@code events.consumer.enabled}, the
 * producer-safe beans ({@code RabbitAdmin}, {@code HandledEventTypesCache}) stay always-on, and a
 * service that asserts {@code events.consumer.enabled=false} while declaring {@code @BitsEventHandler}
 * methods fails fast instead of silently dropping its handlers.
 */
class ConsumerListenerGatingTest {

    private ApplicationContextRunner baseRunner(TypeMappingRegistry registry) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RetryTierPropertiesAutoConfiguration.class,
                        SchemaMessagingConsumerAutoConfiguration.class))
                .withBean(EventConsumerSupport.class, () -> mock(EventConsumerSupport.class))
                .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(SchemaAwareMessageConverter.class, () -> mock(SchemaAwareMessageConverter.class))
                .withBean(TypeMappingRegistry.class, () -> registry)
                .withPropertyValues("spring.application.name=producer-service");
    }

    @Test
    void pureProducer_hasNoListenerBeans_butKeepsRabbitAdminAndHandledEventTypesCache() {
        baseRunner(new TypeMappingRegistry(List.of()))
                .withPropertyValues("events.consumer.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SimpleRabbitListenerContainerFactory.class);
                    assertThat(context).doesNotHaveBean("rabbitListenerContainerFactory");
                    assertThat(context).doesNotHaveBean(BitsEventHandlerRegistrar.class);
                    assertThat(context).doesNotHaveBean(DlxMessageRecoverer.class);
                    assertThat(context).doesNotHaveBean(DlxRoutingAdvice.class);

                    assertThat(context).hasSingleBean(RabbitAdmin.class);
                    assertThat(context).hasSingleBean(HandledEventTypesCache.class);
                });
    }

    @Test
    void consumer_default_wiresFullListenerStack() {
        baseRunner(new TypeMappingRegistry(List.of()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("rabbitListenerContainerFactory");
                    assertThat(context).hasSingleBean(BitsEventHandlerRegistrar.class);
                    assertThat(context).hasSingleBean(DlxMessageRecoverer.class);
                    assertThat(context).hasSingleBean(DlxRoutingAdvice.class);

                    assertThat(context).hasSingleBean(RabbitAdmin.class);
                    assertThat(context).hasSingleBean(HandledEventTypesCache.class);
                });
    }

    @Test
    void pureProducerAssertion_withHandlerPresent_failsFast() {
        // events.consumer.enabled=false declares a pure producer, but a @BitsEventHandler is present.
        // A real TypeMapping for the handler's event type is supplied so the always-on
        // HandledEventTypesCache does not throw first — leaving PureProducerHandlerGuard as the
        // deterministic failure (SmartInitializingSingleton ordering is otherwise unspecified).
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(new TypeMapping(
                TestEvent.class,
                new SchemaCoordinates("events.test", "TestEvent"),
                SchemaType.JSON,
                "test.event",
                "events.test.exchange")));

        baseRunner(registry)
                .withPropertyValues("events.consumer.enabled=false")
                .withBean("producerWithHandler", ProducerWithHandler.class)
                .run(context -> {
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("producerWithHandler")
                            .hasMessageContaining("events.consumer.enabled=false");
                });
    }

    record TestEvent(String id) {}

    static class ProducerWithHandler {
        @BitsEventHandler
        public void onTestEvent(TestEvent event) {
            // no-op
        }
    }
}
