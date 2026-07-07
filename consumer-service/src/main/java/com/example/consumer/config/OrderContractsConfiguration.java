package com.example.consumer.config;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.OrderShipped;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-3.3: one {@link TypeMapping} bean per order event (JSON Schema, spec §10.3/§10.4, code-first D3).
 * Version pinning: set {@code schema.orders.pinned-version} in application.yml to lock all order
 * artifacts to a registered version; leave blank to always resolve the latest (spec §10.5).
 */
@Configuration
public class OrderContractsConfiguration {

    @Value("${schema.orders.pinned-version:}")
    private String pinnedVersion;

    private SchemaCoordinates coords(String artifactId) {
        return (pinnedVersion == null || pinnedVersion.isBlank())
                ? SchemaCoordinates.latest("events.orders", artifactId)
                : new SchemaCoordinates("events.orders", artifactId, pinnedVersion);
    }

    @Bean
    public TypeMapping orderCreatedMapping() {
        return new TypeMapping(OrderCreated.class, coords("OrderCreated"),
                SchemaType.JSON, OrderEventRouting.CREATED_ROUTING_KEY);
    }

    @Bean
    public TypeMapping orderShippedMapping() {
        return new TypeMapping(OrderShipped.class, coords("OrderShipped"),
                SchemaType.JSON, OrderEventRouting.SHIPPED_ROUTING_KEY);
    }

    @Bean
    public TypeMapping orderCancelledMapping() {
        return new TypeMapping(OrderCancelled.class, coords("OrderCancelled"),
                SchemaType.JSON, OrderEventRouting.CANCELLED_ROUTING_KEY);
    }
}
