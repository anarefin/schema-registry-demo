package com.example.messaging.core.config;

import com.example.messaging.core.consumer.BitsEventHandlerRegistrar;
import com.example.messaging.core.consumer.BitsEventHandlerScanner;
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
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
 *       {@code events.consumer.enabled}, default {@code true}): {@code DlxMessageRecoverer},
 *       {@code DlxRoutingAdvice}, {@code rabbitListenerContainerFactory}, and
 *       {@code BitsEventHandlerRegistrar}. A pure producer sets {@code events.consumer.enabled=false}
 *       and carries none of this dead weight.</li>
 * </ul>
 *
 * <p>The flag is a service-level consumer-role toggle, not per-domain: publishing is unaffected
 * (it lives in {@link SchemaMessagingAutoConfiguration}), and which domains/queues a consumer
 * declares stays driven by {@code @BitsEventHandler} discovery. To keep that discovery the single
 * source of truth for what a service handles, {@link PureProducerHandlerGuard} fails startup fast
 * if a service asserts {@code events.consumer.enabled=false} yet declares handler methods —
 * turning silent consumption loss into a clear boot error.
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

    @Bean
    @ConditionalOnMissingBean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandledEventTypesCache handledEventTypesCache(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry) {
        return new HandledEventTypesCache(applicationContext, typeMappingRegistry);
    }

    /**
     * Only instantiated when a service <em>explicitly</em> opts out of consuming
     * ({@code events.consumer.enabled=false}); the default/consumer path pays nothing for it.
     * Fails fast if such a "pure producer" nonetheless declares {@code @BitsEventHandler}
     * methods, so a stale/wrong flag can never silently disable real handlers.
     */
    @Bean
    @ConditionalOnProperty(name = "events.consumer.enabled", havingValue = "false")
    public PureProducerHandlerGuard pureProducerHandlerGuard(ApplicationContext applicationContext) {
        return new PureProducerHandlerGuard(applicationContext);
    }

    /**
     * Listener-only beans, gated behind {@code events.consumer.enabled} (default {@code true}).
     * Absent entirely in a pure producer.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "events.consumer.enabled", havingValue = "true", matchIfMissing = true)
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

    /**
     * Enforces that {@code events.consumer.enabled=false} (pure-producer intent) is consistent
     * with the code: aborts context refresh if any {@code @BitsEventHandler} method is present.
     * Uses the non-instantiating {@link BitsEventHandlerScanner#discoverHandlerBindings} scan, so
     * it never eagerly resolves beans just to check.
     */
    public static class PureProducerHandlerGuard implements SmartInitializingSingleton {

        private final ApplicationContext applicationContext;

        public PureProducerHandlerGuard(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        public void afterSingletonsInstantiated() {
            List<String> handlerBeans = BitsEventHandlerScanner.discoverHandlerBindings(applicationContext).stream()
                    .map(binding -> binding.beanName())
                    .toList();
            if (!handlerBeans.isEmpty()) {
                throw new IllegalStateException(
                        "events.consumer.enabled=false declares a pure producer, but found "
                        + handlerBeans.size() + " @BitsEventHandler binding(s): " + handlerBeans
                        + ". Remove events.consumer.enabled=false or remove the handler methods.");
            }
        }
    }
}
