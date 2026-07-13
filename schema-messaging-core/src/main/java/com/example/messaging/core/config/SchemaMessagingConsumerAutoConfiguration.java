package com.example.messaging.core.config;

import com.example.messaging.core.consumer.BitsEventHandlerRegistrar;
import com.example.messaging.core.consumer.DlxMessageRecoverer;
import com.example.messaging.core.consumer.DlxRoutingAdvice;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for consumer-side AMQP infrastructure (spec §9 / T-5.1).
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code RabbitAdmin} — applies all {@code Queue}/{@code Exchange}/{@code Binding} beans
 *       declared by contract-module auto-configurations idempotently on startup.</li>
 *   <li>{@code DlxMessageRecoverer} — routes failed messages to the retry ladder or DLQ.</li>
 *   <li>{@code DlxRoutingAdvice} — AOP advice that intercepts listener exceptions and
 *       delegates to the recoverer, ACKing the original message.</li>
 *   <li>{@code rabbitListenerContainerFactory} — listener container wired with the
 *       schema-aware converter and DLX routing advice.</li>
 *   <li>{@code BitsEventHandlerRegistrar} — registers {@code @BitsEventHandler} methods as
 *       listener endpoints, resolving their queue from the event's {@code TypeMapping}.</li>
 *   <li>{@code HandledEventTypesCache} — computes the handled {@code TypeMapping} set once and
 *       shares it with topology declaration and the queue depth health indicator.</li>
 * </ul>
 *
 * <p>Each domain now owns its own DLX/retry exchange (spec: contract-owned-amqp-topology.md),
 * declared by that domain's {@code *-contracts} module. There is no single global DLX/retry
 * exchange to configure here — {@code DlxMessageRecoverer} derives the correct per-domain
 * exchange from each message's received exchange instead.
 *
 * <p>Retry tier TTLs are bound once by {@link RetryTierPropertiesAutoConfiguration}, not
 * re-declared here — see {@link RetryTierProperties}.
 */
@AutoConfiguration(after = {SchemaMessagingAutoConfiguration.class, RetryTierPropertiesAutoConfiguration.class})
public class SchemaMessagingConsumerAutoConfiguration {

    @Value("${spring.application.name}") private String serviceName;

    @Bean
    @ConditionalOnMissingBean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DlxMessageRecoverer dlxMessageRecoverer(
            EventConsumerSupport consumerSupport,
            RabbitTemplate rabbitTemplate,
            RetryTierProperties retryTierProperties) {
        return new DlxMessageRecoverer(consumerSupport, rabbitTemplate, retryTierProperties.toArray(), serviceName);
    }

    @Bean
    @ConditionalOnMissingBean
    public DlxRoutingAdvice dlxRoutingAdvice(DlxMessageRecoverer dlxMessageRecoverer) {
        return new DlxRoutingAdvice(dlxMessageRecoverer);
    }

    @Bean
    @ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SchemaAwareMessageConverter converter,
            DlxRoutingAdvice dlxRoutingAdvice) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(dlxRoutingAdvice);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public BitsEventHandlerRegistrar bitsEventHandlerRegistrar(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry) {
        return new BitsEventHandlerRegistrar(applicationContext, typeMappingRegistry, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandledEventTypesCache handledEventTypesCache(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry) {
        return new HandledEventTypesCache(applicationContext, typeMappingRegistry);
    }
}
