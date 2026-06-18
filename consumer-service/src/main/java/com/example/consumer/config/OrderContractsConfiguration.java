package com.example.consumer.config;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TypeMapping for OrderCreated (JSON Schema): binds the Java type to its Apicurio coordinates,
 * wire format, and AMQP routing key. Always resolves the latest registered version.
 */
@Configuration
public class OrderContractsConfiguration {

    @Bean
    public TypeMapping orderCreatedMapping() {
        SchemaCoordinates coords = SchemaCoordinates.latest("events.orders", "OrderCreated");
        return new TypeMapping(OrderCreated.class, coords, SchemaType.JSON, OrderEventRouting.ROUTING_KEY);
    }
}
