package com.example.consumer.config;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-3.3 / T-4.7: TypeMapping for OrderCreated (Protobuf, spec §10.3/§10.4).
 * Version pinning: set schema.orders.pinned-version in application.yml to lock to a specific
 * registered version; leave blank to always resolve the latest (spec §10.5).
 */
@Configuration
public class OrderContractsConfiguration {

    @Value("${schema.orders.pinned-version:}")
    private String pinnedVersion;

    @Bean
    public TypeMapping orderCreatedMapping() {
        SchemaCoordinates coords = (pinnedVersion == null || pinnedVersion.isBlank())
                ? SchemaCoordinates.latest("events.orders", "OrderCreated")
                : new SchemaCoordinates("events.orders", "OrderCreated", pinnedVersion);
        return new TypeMapping(OrderCreated.class, coords, SchemaType.PROTOBUF, OrderEventRouting.ROUTING_KEY);
    }
}
