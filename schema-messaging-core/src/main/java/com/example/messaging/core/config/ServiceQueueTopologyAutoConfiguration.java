package com.example.messaging.core.config;

import com.example.amqp.topology.EventTopologyFactory;
import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.BitsEventHandler;
import com.example.messaging.core.consumer.BitsEventHandlerScanner;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;

/**
 * Declares per-service dedicated queues, DLQs, and retry ladders for every event type
 * handled by {@link BitsEventHandler} methods in this application.
 *
 * <p>Queue naming follows {@code {routingKey}.{serviceName}.queue}. Only events with
 * registered handlers are provisioned — producer services with no listeners declare nothing,
 * avoiding accidental fan-out copies on the topic exchange.
 */
@AutoConfiguration(after = {SchemaMessagingConsumerAutoConfiguration.class, RetryTierPropertiesAutoConfiguration.class})
public class ServiceQueueTopologyAutoConfiguration {

    @Bean
    ServiceQueueTopologyConfigurer serviceQueueTopologyConfigurer(
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry,
            RabbitAdmin rabbitAdmin,
            @Value("${spring.application.name}") String serviceName,
            RetryTierProperties retryTierProperties) {
        return new ServiceQueueTopologyConfigurer(
                applicationContext, typeMappingRegistry, rabbitAdmin, serviceName, retryTierProperties.toArray());
    }

    static class ServiceQueueTopologyConfigurer implements SmartInitializingSingleton {

        private final ApplicationContext applicationContext;
        private final TypeMappingRegistry typeMappingRegistry;
        private final RabbitAdmin rabbitAdmin;
        private final String serviceName;
        private final long[] tierTtls;

        ServiceQueueTopologyConfigurer(
                ApplicationContext applicationContext,
                TypeMappingRegistry typeMappingRegistry,
                RabbitAdmin rabbitAdmin,
                String serviceName,
                long[] tierTtls) {
            this.applicationContext = applicationContext;
            this.typeMappingRegistry = typeMappingRegistry;
            this.rabbitAdmin = rabbitAdmin;
            this.serviceName = serviceName;
            this.tierTtls = tierTtls;
        }

        /** Exposed so tests can verify this configurer shares a single {@code RetryTierProperties} source. */
        long[] tierTtls() {
            return tierTtls;
        }

        @Override
        public void afterSingletonsInstantiated() {
            Set<TypeMapping> handlerMappings =
                    BitsEventHandlerScanner.discoverHandledTypeMappings(applicationContext, typeMappingRegistry);
            if (handlerMappings.isEmpty()) {
                return;
            }
            for (TypeMapping mapping : handlerMappings) {
                declareForMapping(mapping);
            }
        }

        private void declareForMapping(TypeMapping mapping) {
            String exchangeName = mapping.exchange();
            TopicExchange mainExchange = new TopicExchange(exchangeName, true, false);
            TopicExchange dlx = new TopicExchange(TopologyNaming.dlxExchangeName(exchangeName), true, false);
            TopicExchange retryExchange =
                    new TopicExchange(TopologyNaming.retryExchangeName(exchangeName), true, false);

            // Declare the exchanges ourselves rather than relying on the domain contracts module's
            // exchange @Beans having already auto-declared via Spring AMQP's own ContextRefreshedEvent/
            // ConnectionListener hook — that ordering relative to this SmartInitializingSingleton isn't
            // guaranteed, and declareExchange is idempotent so this is safe even if they also declare it.
            rabbitAdmin.declareExchange(mainExchange);
            rabbitAdmin.declareExchange(dlx);
            rabbitAdmin.declareExchange(retryExchange);

            List<Declarable> declarables = EventTopologyFactory.declarablesForEvent(
                    mapping.routingKey(), serviceName, mainExchange, dlx, retryExchange, tierTtls);
            for (Declarable declarable : declarables) {
                declare(rabbitAdmin, declarable);
            }
        }

        private static void declare(RabbitAdmin rabbitAdmin, Declarable declarable) {
            switch (declarable) {
                case Queue queue -> rabbitAdmin.declareQueue(queue);
                case Binding binding -> rabbitAdmin.declareBinding(binding);
                default -> throw new IllegalArgumentException("Unsupported declarable: " + declarable);
            }
        }
    }
}
