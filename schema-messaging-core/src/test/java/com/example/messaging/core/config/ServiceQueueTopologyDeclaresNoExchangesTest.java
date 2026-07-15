package com.example.messaging.core.config;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link ServiceQueueTopologyAutoConfiguration} declares only queues + bindings and
 * <em>never</em> declares exchanges: exchange ownership belongs to the domain's {@code *-contracts}
 * module. Core builds the main/DLX/retry {@code TopicExchange} objects locally (from the mapping's
 * exchange name) solely to feed the topology factory — a binding needs only the exchange name.
 */
class ServiceQueueTopologyDeclaresNoExchangesTest {

    private static final long[] TIER_TTLS = {5000L, 30000L, 300000L};

    private static final String ORDERS_EXCHANGE = "events.orders.exchange";
    private static final String CUSTOMERS_EXCHANGE = "events.customers.exchange";

    @Test
    void neverDeclaresExchangesOnlyQueuesAndBindings() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        HandledEventTypesCache cache = cacheWithMappings(
                orderMapping("orders.created"),
                orderMapping("orders.shipped"),
                orderMapping("orders.cancelled"));

        configurer(cache, rabbitAdmin).afterSingletonsInstantiated();

        verify(rabbitAdmin, never()).declareExchange(any());
        verify(rabbitAdmin, times(3 * 5)).declareQueue(any());
        verify(rabbitAdmin, times(3 * 6)).declareBinding(any());
    }

    @Test
    void neverDeclaresExchangesAcrossMultipleDomains() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        HandledEventTypesCache cache = cacheWithMappings(
                orderMapping("orders.created"),
                orderMapping("orders.shipped"),
                customerMapping("customers.registered"),
                customerMapping("customers.tier-changed"));

        configurer(cache, rabbitAdmin).afterSingletonsInstantiated();

        verify(rabbitAdmin, never()).declareExchange(any());
        verify(rabbitAdmin, times(4 * 5)).declareQueue(any());
        verify(rabbitAdmin, times(4 * 6)).declareBinding(any());
    }

    private static ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer configurer(
            HandledEventTypesCache cache, RabbitAdmin rabbitAdmin) {
        return new ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer(
                cache, rabbitAdmin, "consumer-service", true, TIER_TTLS);
    }

    private static HandledEventTypesCache cacheWithMappings(TypeMapping... mappings) {
        HandledEventTypesCache cache = mock(HandledEventTypesCache.class);
        when(cache.handledTypeMappings()).thenReturn(Set.of(mappings));
        return cache;
    }

    private static TypeMapping orderMapping(String routingKey) {
        return new TypeMapping(
                Object.class,
                new SchemaCoordinates("events.orders", "OrderEvent"),
                SchemaType.JSON,
                routingKey,
                ORDERS_EXCHANGE);
    }

    private static TypeMapping customerMapping(String routingKey) {
        return new TypeMapping(
                Object.class,
                new SchemaCoordinates("events.customers", "CustomerEvent"),
                SchemaType.JSON,
                routingKey,
                CUSTOMERS_EXCHANGE);
    }
}
