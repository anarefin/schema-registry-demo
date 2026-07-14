package com.example.contracts.orders.topology;

import com.example.amqp.topology.mapping.Mappings;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.OrderFulfilled;
import com.example.contracts.orders.OrderShipped;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Declares the {@code events.orders} domain's {@link TypeMapping} beans — one per order event,
 * linking the Java record to its schema coordinates, schema type, and routing key. This module
 * self-activates this wiring rather than duplicating it in producer-service/consumer-service
 * (pattern established by ADR-0005); {@link TypeMapping} itself lives in {@code event-contract-kit},
 * not core, so this module needs no dependency on schema-messaging-core (ADR-0006).
 * Self-contained, like {@link OrderTopologyAutoConfiguration}: enumerates its own events directly
 * rather than iterating a shared registry it has no visibility into.
 */
@AutoConfiguration
public class OrderTypeMappingAutoConfiguration {

    private static final Mappings M = Mappings.forDomain("events.orders", OrderEventRouting.EXCHANGE);

    @Bean("orderCreatedMapping")
    @ConditionalOnMissingBean(name = "orderCreatedMapping")
    public TypeMapping orderCreatedMapping() {
        return M.json(OrderCreated.class, OrderEventRouting.CREATED_ROUTING_KEY);
    }

    @Bean("orderShippedMapping")
    @ConditionalOnMissingBean(name = "orderShippedMapping")
    public TypeMapping orderShippedMapping() {
        return M.json(OrderShipped.class, OrderEventRouting.SHIPPED_ROUTING_KEY);
    }

    @Bean("orderCancelledMapping")
    @ConditionalOnMissingBean(name = "orderCancelledMapping")
    public TypeMapping orderCancelledMapping() {
        return M.json(OrderCancelled.class, OrderEventRouting.CANCELLED_ROUTING_KEY);
    }

    @Bean("orderFulfilledMapping")
    @ConditionalOnMissingBean(name = "orderFulfilledMapping")
    public TypeMapping orderFulfilledMapping() {
        return M.json(OrderFulfilled.class, OrderEventRouting.FULFILLED_ROUTING_KEY);
    }
}
