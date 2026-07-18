package com.example.messaging.core.config;

import com.example.messaging.core.consumer.BitsEventHandlerRegistrar;
import com.example.messaging.core.consumer.BitsEventHandlerScanner;
import com.example.messaging.core.consumer.DlxMessageRecoverer;
import com.example.messaging.core.consumer.DlxRoutingAdvice;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for consumer-side AMQP infrastructure (spec §9 / T-5.1).
 *
 * <p>Beans are split by producer/consumer boundary (spec 09 / ARCH-001):
 * <ul>
 *   <li><b>Always-on</b> (this outer class): {@code RabbitAdmin} — applies all
 *       {@code Queue}/{@code Exchange}/{@code Binding} beans declared by contract-module
 *       auto-configurations idempotently on startup; needed by a producer to declare its
 *       exchanges. {@code HandledEventTypesCache} — computes the handled {@code TypeMapping}
 *       set once (empty for a pure producer) and shares it with topology declaration and the
 *       queue depth health indicator.</li>
 *   <li><b>Listener-only</b> ({@link ListenerConfiguration}, gated behind
 *       {@link OnBitsEventHandlerPresentCondition}): {@code DlxMessageRecoverer},
 *       {@code DlxRoutingAdvice}, {@code rabbitListenerContainerFactory}, and
 *       {@code BitsEventHandlerRegistrar}. A pure producer with no {@code @BitsEventHandler}
 *       methods carries none of this dead weight.</li>
 * </ul>
 *
 * <p>Publishing is unaffected (it lives in {@link SchemaMessagingAutoConfiguration}), and which
 * domains/queues a consumer declares stays driven by {@code @BitsEventHandler} discovery — the
 * same signal that gates the listener stack.
 *
 * <p>Each domain now owns its own DLX/retry exchange (spec: contract-owned-amqp-topology.md),
 * declared by that domain's {@code *-contracts} module. There is no single global DLX/retry
 * exchange to configure here — {@code DlxMessageRecoverer} derives the correct per-domain
 * exchange from each message's received exchange instead.
 *
 * <p>Retry tier TTLs are bound once by {@link RetryTierPropertiesAutoConfiguration}, not
 * re-declared here — see {@link RetryTierProperties}.
 */
@AutoConfiguration(
        after = {SchemaMessagingAutoConfiguration.class, RetryTierPropertiesAutoConfiguration.class},
        // String form — core depends on spring-boot-autoconfigure only, not spring-boot-amqp.
        beforeName = "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
public class SchemaMessagingConsumerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AmqpAdmin.class)
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory, ApplicationContext applicationContext) {
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        // Consumers that boot before the publisher need ignore=true so bindings to not-yet-declared
        // exchanges self-heal on reconnect. Producers must fail fast on exchange declare failures —
        // only enable ignore when this app has @BitsEventHandler methods.
        if (!BitsEventHandlerScanner.discoverHandledJavaTypes(applicationContext).isEmpty()) {
            rabbitAdmin.setIgnoreDeclarationExceptions(true);
        }
        return rabbitAdmin;
    }

    @Bean
    @ConditionalOnMissingBean
    public HandledEventTypesCache handledEventTypesCache(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry) {
        return new HandledEventTypesCache(applicationContext, typeMappingRegistry);
    }

    /**
     * Listener-only beans, gated on {@code @BitsEventHandler} presence.
     * Absent entirely in a pure producer.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(OnBitsEventHandlerPresentCondition.class)
    public static class ListenerConfiguration {

        @Value("${spring.application.name}") private String serviceName;

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
    }
}
