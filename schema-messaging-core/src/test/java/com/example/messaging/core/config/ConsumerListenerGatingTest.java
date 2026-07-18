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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the producer/consumer boundary of {@link SchemaMessagingConsumerAutoConfiguration}
 * (spec 09 / ARCH-001): the listener stack is gated on {@code @BitsEventHandler} presence, the
 * producer-safe beans ({@code RabbitAdmin}, {@code HandledEventTypesCache}) stay always-on.
 */
class ConsumerListenerGatingTest {

    private static final TypeMapping TEST_EVENT_MAPPING = new TypeMapping(
            TestEvent.class,
            new SchemaCoordinates("events.test", "TestEvent"),
            SchemaType.JSON,
            "test.event",
            "events.test.exchange");

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
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SimpleRabbitListenerContainerFactory.class);
                    assertThat(context).doesNotHaveBean("rabbitListenerContainerFactory");
                    assertThat(context).doesNotHaveBean(BitsEventHandlerRegistrar.class);
                    assertThat(context).doesNotHaveBean(DlxMessageRecoverer.class);
                    assertThat(context).doesNotHaveBean(DlxRoutingAdvice.class);

                    assertThat(context).hasSingleBean(RabbitAdmin.class);
                    assertThat(ignoreDeclarationExceptions(context.getBean(RabbitAdmin.class))).isFalse();
                    assertThat(context).hasSingleBean(HandledEventTypesCache.class);
                });
    }

    @Test
    void consumer_withHandler_wiresFullListenerStack() {
        baseRunner(new TypeMappingRegistry(List.of(TEST_EVENT_MAPPING)))
                .withBean("consumerWithHandler", ConsumerWithHandler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("rabbitListenerContainerFactory");
                    assertThat(context).hasSingleBean(BitsEventHandlerRegistrar.class);
                    assertThat(context).hasSingleBean(DlxMessageRecoverer.class);
                    assertThat(context).hasSingleBean(DlxRoutingAdvice.class);

                    assertThat(context).hasSingleBean(RabbitAdmin.class);
                    assertThat(ignoreDeclarationExceptions(context.getBean(RabbitAdmin.class))).isTrue();
                    assertThat(context).hasSingleBean(HandledEventTypesCache.class);
                });
    }

    private static boolean ignoreDeclarationExceptions(RabbitAdmin admin) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.getField(admin, "ignoreDeclarationExceptions"));
    }

    record TestEvent(String id) {}

    static class ConsumerWithHandler {
        @BitsEventHandler
        public void onTestEvent(TestEvent event) {
            // no-op
        }
    }
}
