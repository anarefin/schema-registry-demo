package com.example.consumer.config;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.OrderShipped;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-3.3: one {@link TypeMapping} bean per order event (JSON Schema, spec §10.3/§10.4, code-first D3).
 */
@Configuration
public class OrderContractsConfiguration {

    private static SchemaCoordinates coords(String artifactId) {
        return new SchemaCoordinates("events.orders", artifactId);
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
