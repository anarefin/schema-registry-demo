package com.example.messaging.core.config;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link ServiceQueueTopologyAutoConfiguration} decommissions pre-per-service shared-domain
 * queues before declaring the new per-service topology.
 */
class ServiceQueueTopologyLegacyDecommissionTest {

    private static final long[] TIER_TTLS = {5000L, 30000L, 300000L};
    private static final String ORDERS_EXCHANGE = "events.orders.exchange";

    @Test
    void deletesLegacyQueuesForEachHandledEventBeforeDeclaringPerServiceTopology() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        HandledEventTypesCache cache = cacheWithMappings(
                orderMapping("orders.created"), orderMapping("orders.shipped"));

        configurer(cache, rabbitAdmin, true).afterSingletonsInstantiated();

        // handledTypeMappings() is an unordered Set, so cross-event deletion order is not
        // specified. The contract is per-event: every legacy queue for a handled event (in
        // tier order) is deleted before any per-service queue is declared. Verify each event
        // with its own InOrder so the assertion holds regardless of set iteration order.
        for (String routingKey : List.of("orders.created", "orders.shipped")) {
            InOrder inOrder = inOrder(rabbitAdmin);
            for (String queueName : TopologyNaming.legacySharedDomainQueueNames(routingKey, 3)) {
                inOrder.verify(rabbitAdmin).deleteQueue(queueName);
            }
            inOrder.verify(rabbitAdmin).declareQueue(org.mockito.ArgumentMatchers.any());
        }
        verify(rabbitAdmin, org.mockito.Mockito.times(10)).deleteQueue(org.mockito.ArgumentMatchers.anyString());
        verify(rabbitAdmin, org.mockito.Mockito.atLeastOnce()).declareQueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noOpWhenNoHandlers() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        HandledEventTypesCache cache = cacheWithMappings();

        configurer(cache, rabbitAdmin, true).afterSingletonsInstantiated();

        verify(rabbitAdmin, never()).deleteQueue(org.mockito.ArgumentMatchers.anyString());
        verify(rabbitAdmin, never()).declareQueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsDecommissionWhenDisabled() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        HandledEventTypesCache cache = cacheWithMappings(orderMapping("orders.created"));

        configurer(cache, rabbitAdmin, false).afterSingletonsInstantiated();

        verify(rabbitAdmin, never()).deleteQueue(org.mockito.ArgumentMatchers.anyString());
        verify(rabbitAdmin, org.mockito.Mockito.atLeastOnce()).declareQueue(org.mockito.ArgumentMatchers.any());
    }

    private static ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer configurer(
            HandledEventTypesCache cache, RabbitAdmin rabbitAdmin, boolean decommissionLegacyQueues) {
        return new ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer(
                cache,
                rabbitAdmin,
                "consumer-service",
                decommissionLegacyQueues,
                TIER_TTLS);
    }

    private static HandledEventTypesCache cacheWithMappings(TypeMapping... mappings) {
        HandledEventTypesCache cache = mock(HandledEventTypesCache.class);
        when(cache.handledTypeMappings()).thenReturn(Set.copyOf(java.util.Arrays.asList(mappings)));
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
}
