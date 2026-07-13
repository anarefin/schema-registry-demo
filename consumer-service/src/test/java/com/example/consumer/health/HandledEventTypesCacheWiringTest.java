package com.example.consumer.health;

import com.example.messaging.core.config.RetryTierPropertiesAutoConfiguration;
import com.example.messaging.core.config.SchemaMessagingConsumerAutoConfiguration;
import com.example.messaging.core.config.ServiceQueueTopologyAutoConfiguration;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves that {@code ServiceQueueTopologyAutoConfiguration}'s topology declarer and
 * {@link QueueDepthHealthIndicator} resolve the exact same Spring-managed
 * {@link HandledEventTypesCache} bean, rather than each independently scanning the application
 * context for {@code @BitsEventHandler} methods.
 */
class HandledEventTypesCacheWiringTest {

    @Test
    void topologyDeclarationAndHealthIndicator_shareTheSameHandledEventTypesCacheBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RetryTierPropertiesAutoConfiguration.class,
                        SchemaMessagingConsumerAutoConfiguration.class,
                        ServiceQueueTopologyAutoConfiguration.class))
                .withUserConfiguration(QueueDepthHealthIndicatorTestConfig.class)
                .withBean(EventConsumerSupport.class, () -> mock(EventConsumerSupport.class))
                .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(SchemaAwareMessageConverter.class, () -> mock(SchemaAwareMessageConverter.class))
                .withBean(TypeMappingRegistry.class, () -> new TypeMappingRegistry(List.of()))
                .withBean(RabbitAdmin.class, () -> mock(RabbitAdmin.class))
                .withPropertyValues("spring.application.name=consumer-service")
                .run(context -> {
                    assertThat(context.getBeansOfType(HandledEventTypesCache.class)).hasSize(1);

                    HandledEventTypesCache cache = context.getBean(HandledEventTypesCache.class);
                    QueueDepthHealthIndicator healthIndicator = context.getBean(QueueDepthHealthIndicator.class);

                    assertThat(healthIndicator.handledEventTypesCacheForTest()).isSameAs(cache);
                });
    }

    @Configuration
    static class QueueDepthHealthIndicatorTestConfig {
        @Bean
        QueueDepthHealthIndicator queueDepthHealthIndicator(
                RabbitAdmin rabbitAdmin, HandledEventTypesCache cache) {
            return new QueueDepthHealthIndicator(rabbitAdmin, cache, "consumer-service");
        }
    }
}
