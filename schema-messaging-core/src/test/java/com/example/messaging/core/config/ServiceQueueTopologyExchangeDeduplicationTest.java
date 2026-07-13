package com.example.messaging.core.config;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves {@link ServiceQueueTopologyAutoConfiguration} declares each distinct exchange at most
 * once and uses the contracts-owned {@link TopicExchange} beans — not a second hardcoded copy.
 */
class ServiceQueueTopologyExchangeDeduplicationTest {

    private static final long[] TIER_TTLS = {5000L, 30000L, 300000L};

    private static final String ORDERS_EXCHANGE = "events.orders.exchange";
    private static final String CUSTOMERS_EXCHANGE = "events.customers.exchange";

    @Test
    void declareExchangeOncePerDistinctExchangeWhenMultipleEventTypesShareDomain() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        List<TopicExchange> exchanges = orderContractExchanges();
        HandledEventTypesCache cache = cacheWithMappings(
                orderMapping("orders.created"),
                orderMapping("orders.shipped"),
                orderMapping("orders.cancelled"));

        configurer(cache, rabbitAdmin, exchanges).afterSingletonsInstantiated();

        assertThat(capturedExchangeNames(rabbitAdmin)).containsExactlyInAnyOrder(
                ORDERS_EXCHANGE,
                TopologyNaming.dlxExchangeName(ORDERS_EXCHANGE),
                TopologyNaming.retryExchangeName(ORDERS_EXCHANGE));
        verify(rabbitAdmin, times(3)).declareExchange(org.mockito.ArgumentMatchers.any());
        verify(rabbitAdmin, times(3 * 5)).declareQueue(org.mockito.ArgumentMatchers.any());
        verify(rabbitAdmin, times(3 * 6)).declareBinding(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void declareExchangeBoundedByDistinctNamesAcrossDomains() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        List<TopicExchange> exchanges = List.of(
                new TopicExchange(ORDERS_EXCHANGE, true, false),
                new TopicExchange(TopologyNaming.dlxExchangeName(ORDERS_EXCHANGE), true, false),
                new TopicExchange(TopologyNaming.retryExchangeName(ORDERS_EXCHANGE), true, false),
                new TopicExchange(CUSTOMERS_EXCHANGE, true, false),
                new TopicExchange(TopologyNaming.dlxExchangeName(CUSTOMERS_EXCHANGE), true, false),
                new TopicExchange(TopologyNaming.retryExchangeName(CUSTOMERS_EXCHANGE), true, false));
        HandledEventTypesCache cache = cacheWithMappings(
                orderMapping("orders.created"),
                orderMapping("orders.shipped"),
                customerMapping("customers.registered"),
                customerMapping("customers.tier-changed"));

        configurer(cache, rabbitAdmin, exchanges).afterSingletonsInstantiated();

        assertThat(capturedExchangeNames(rabbitAdmin)).hasSize(6);
        verify(rabbitAdmin, times(6)).declareExchange(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void usesContractsExchangeBeanPropertiesNotHardcodedCopy() {
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        TopicExchange mainFromContracts = new TopicExchange(ORDERS_EXCHANGE, false, true);
        List<TopicExchange> exchanges = List.of(
                mainFromContracts,
                new TopicExchange(TopologyNaming.dlxExchangeName(ORDERS_EXCHANGE), true, false),
                new TopicExchange(TopologyNaming.retryExchangeName(ORDERS_EXCHANGE), true, false));
        HandledEventTypesCache cache = cacheWithMappings(orderMapping("orders.created"));

        configurer(cache, rabbitAdmin, exchanges).afterSingletonsInstantiated();

        org.mockito.ArgumentCaptor<TopicExchange> captor = org.mockito.ArgumentCaptor.forClass(TopicExchange.class);
        verify(rabbitAdmin, times(3)).declareExchange(captor.capture());
        TopicExchange declared = captor.getAllValues().stream()
                .filter(e -> ORDERS_EXCHANGE.equals(e.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(declared.isDurable()).isFalse();
        assertThat(declared.isAutoDelete()).isTrue();
        assertThat(declared).isSameAs(mainFromContracts);
    }

    private static ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer configurer(
            HandledEventTypesCache cache, RabbitAdmin rabbitAdmin, List<TopicExchange> exchanges) {
        return new ServiceQueueTopologyAutoConfiguration.ServiceQueueTopologyConfigurer(
                cache, rabbitAdmin, "consumer-service", TIER_TTLS, exchanges);
    }

    private static HandledEventTypesCache cacheWithMappings(TypeMapping... mappings) {
        HandledEventTypesCache cache = mock(HandledEventTypesCache.class);
        when(cache.handledTypeMappings()).thenReturn(Set.of(mappings));
        return cache;
    }

    private static List<TopicExchange> orderContractExchanges() {
        return List.of(
                new TopicExchange(ORDERS_EXCHANGE, true, false),
                new TopicExchange(TopologyNaming.dlxExchangeName(ORDERS_EXCHANGE), true, false),
                new TopicExchange(TopologyNaming.retryExchangeName(ORDERS_EXCHANGE), true, false));
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

    private static List<String> capturedExchangeNames(RabbitAdmin rabbitAdmin) {
        org.mockito.ArgumentCaptor<TopicExchange> captor = org.mockito.ArgumentCaptor.forClass(TopicExchange.class);
        verify(rabbitAdmin, org.mockito.Mockito.atLeastOnce()).declareExchange(captor.capture());
        return captor.getAllValues().stream().map(TopicExchange::getName).toList();
    }
}
