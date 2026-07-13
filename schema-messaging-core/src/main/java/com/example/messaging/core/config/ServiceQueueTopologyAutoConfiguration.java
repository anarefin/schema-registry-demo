package com.example.messaging.core.config;

import com.example.amqp.topology.EventTopologyFactory;
import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.BitsEventHandler;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            HandledEventTypesCache handledEventTypesCache,
            RabbitAdmin rabbitAdmin,
            @Value("${spring.application.name}") String serviceName,
            RetryTierProperties retryTierProperties,
            List<TopicExchange> topicExchanges) {
        return new ServiceQueueTopologyConfigurer(
                handledEventTypesCache, rabbitAdmin, serviceName, retryTierProperties.toArray(), topicExchanges);
    }

    static class ServiceQueueTopologyConfigurer implements SmartInitializingSingleton {

        private final HandledEventTypesCache handledEventTypesCache;
        private final RabbitAdmin rabbitAdmin;
        private final String serviceName;
        private final long[] tierTtls;
        private final Map<String, TopicExchange> exchangesByName;

        ServiceQueueTopologyConfigurer(
                HandledEventTypesCache handledEventTypesCache,
                RabbitAdmin rabbitAdmin,
                String serviceName,
                long[] tierTtls,
                List<TopicExchange> topicExchanges) {
            this.handledEventTypesCache = handledEventTypesCache;
            this.rabbitAdmin = rabbitAdmin;
            this.serviceName = serviceName;
            this.tierTtls = tierTtls;
            this.exchangesByName = topicExchanges.stream()
                    .collect(Collectors.toUnmodifiableMap(TopicExchange::getName, Function.identity(), (a, b) -> a));
        }

        /** Exposed so tests can verify this configurer shares a single {@code RetryTierProperties} source. */
        long[] tierTtls() {
            return tierTtls;
        }

        @Override
        public void afterSingletonsInstantiated() {
            Set<TypeMapping> handlerMappings = handledEventTypesCache.handledTypeMappings();
            if (handlerMappings.isEmpty()) {
                return;
            }
            Set<String> declaredExchanges = new HashSet<>();
            for (TypeMapping mapping : handlerMappings) {
                declareForMapping(mapping, declaredExchanges);
            }
        }

        private void declareForMapping(TypeMapping mapping, Set<String> declaredExchanges) {
            String exchangeName = mapping.exchange();
            TopicExchange mainExchange = requireExchange(exchangeName);
            TopicExchange dlx = requireExchange(TopologyNaming.dlxExchangeName(exchangeName));
            TopicExchange retryExchange = requireExchange(TopologyNaming.retryExchangeName(exchangeName));

            // Declare each distinct exchange at most once. Properties come from the contracts
            // module TopicExchange beans — not a second hardcoded copy here. declareExchange is
            // idempotent, so this is safe even if Spring AMQP's own auto-declare hook also ran.
            declareExchangeOnce(mainExchange, declaredExchanges);
            declareExchangeOnce(dlx, declaredExchanges);
            declareExchangeOnce(retryExchange, declaredExchanges);

            List<Declarable> declarables = EventTopologyFactory.declarablesForEvent(
                    mapping.routingKey(), serviceName, mainExchange, dlx, retryExchange, tierTtls);
            for (Declarable declarable : declarables) {
                declare(rabbitAdmin, declarable);
            }
        }

        private TopicExchange requireExchange(String exchangeName) {
            TopicExchange exchange = exchangesByName.get(exchangeName);
            if (exchange == null) {
                throw new IllegalStateException("No TopicExchange bean registered for exchange: " + exchangeName);
            }
            return exchange;
        }

        private void declareExchangeOnce(TopicExchange exchange, Set<String> declaredExchanges) {
            if (declaredExchanges.add(exchange.getName())) {
                rabbitAdmin.declareExchange(exchange);
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
