package com.example.messaging.core.config;

import com.example.messaging.core.consumer.DlxMessageRecoverer;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves that {@code DlxMessageRecoverer} (via {@link SchemaMessagingConsumerAutoConfiguration})
 * and {@code ServiceQueueTopologyConfigurer} (via {@link ServiceQueueTopologyAutoConfiguration})
 * both derive their retry-tier TTL array from the same {@link RetryTierProperties} instance,
 * rather than each binding {@code events.retry.tier*.ms} independently.
 */
class RetryTierWiringTest {

    @Test
    void bothConsumers_receiveIdenticalTierArrayFromSharedRetryTierProperties() {
        RetryTierProperties sharedProperties = new RetryTierProperties(111L, 222L, 333L);

        DlxMessageRecoverer recoverer = new SchemaMessagingConsumerAutoConfiguration()
                .dlxMessageRecoverer(mock(EventConsumerSupport.class), mock(RabbitTemplate.class), sharedProperties);

        ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer configurer =
                new ServiceQueueTopologyAutoConfiguration().serviceQueueTopologyConfigurer(
                        mock(HandledEventTypesCache.class),
                        mock(RabbitAdmin.class),
                        "consumer-service",
                        false,
                        sharedProperties,
                        List.of());

        assertThat(recoverer.retryDelaysMs()).containsExactly(111L, 222L, 333L);
        assertThat(configurer.tierTtls()).containsExactly(111L, 222L, 333L);
        assertThat(recoverer.retryDelaysMs()).containsExactly(configurer.tierTtls());
    }

    /**
     * Proves the sharing holds under real Spring dependency injection, not just when the same
     * Java object is handed to both bean methods by hand (see the test above): composes the
     * actual {@code RetryTierPropertiesAutoConfiguration} + both consumer auto-configurations in
     * one context and confirms exactly one {@code RetryTierProperties} bean is resolved by both.
     */
    @Test
    void bothConsumers_resolveTheSameSpringManagedRetryTierPropertiesBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RetryTierPropertiesAutoConfiguration.class,
                        SchemaMessagingConsumerAutoConfiguration.class,
                        ServiceQueueTopologyAutoConfiguration.class))
                .withBean(EventConsumerSupport.class, () -> mock(EventConsumerSupport.class))
                .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(SchemaAwareMessageConverter.class, () -> mock(SchemaAwareMessageConverter.class))
                .withBean(TypeMappingRegistry.class, () -> mock(TypeMappingRegistry.class))
                .withPropertyValues(
                        "spring.application.name=consumer-service",
                        "events.retry.tier0.ms=111",
                        "events.retry.tier1.ms=222",
                        "events.retry.tier2.ms=333")
                .run(context -> {
                    assertThat(context.getBeansOfType(RetryTierProperties.class)).hasSize(1);

                    DlxMessageRecoverer recoverer = context.getBean(DlxMessageRecoverer.class);
                    ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer configurer =
                            context.getBean(ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer.class);

                    assertThat(recoverer.retryDelaysMs()).containsExactly(111L, 222L, 333L);
                    assertThat(configurer.tierTtls()).containsExactly(111L, 222L, 333L);
                });
    }
}
