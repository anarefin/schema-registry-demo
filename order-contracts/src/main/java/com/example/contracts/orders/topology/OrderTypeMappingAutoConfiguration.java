package com.example.contracts.orders.topology;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.OrderShipped;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
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

    private static SchemaCoordinates coords(String artifactId) {
        return new SchemaCoordinates("events.orders", artifactId);
    }

    @Bean("orderCreatedMapping")
    @ConditionalOnMissingBean(name = "orderCreatedMapping")
    public TypeMapping orderCreatedMapping() {
        return new TypeMapping(OrderCreated.class, coords("OrderCreated"),
                SchemaType.JSON, OrderEventRouting.CREATED_ROUTING_KEY);
    }

    @Bean("orderShippedMapping")
    @ConditionalOnMissingBean(name = "orderShippedMapping")
    public TypeMapping orderShippedMapping() {
        return new TypeMapping(OrderShipped.class, coords("OrderShipped"),
                SchemaType.JSON, OrderEventRouting.SHIPPED_ROUTING_KEY);
    }

    @Bean("orderCancelledMapping")
    @ConditionalOnMissingBean(name = "orderCancelledMapping")
    public TypeMapping orderCancelledMapping() {
        return new TypeMapping(OrderCancelled.class, coords("OrderCancelled"),
                SchemaType.JSON, OrderEventRouting.CANCELLED_ROUTING_KEY);
    }
}
