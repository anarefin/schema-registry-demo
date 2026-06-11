package com.example.consumer.config;

import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-2.3 / T-4.7: TypeMapping for CustomerRegistered (JSON Schema, spec §10.3/§10.4).
 * Version pinning: set schema.customers.pinned-version in application.yml to lock to a specific
 * registered version; leave blank to always resolve the latest (spec §10.5).
 */
@Configuration
public class CustomerContractsConfiguration {

    @Value("${schema.customers.pinned-version:}")
    private String pinnedVersion;

    @Bean
    public TypeMapping customerRegisteredMapping() {
        SchemaCoordinates coords = (pinnedVersion == null || pinnedVersion.isBlank())
                ? SchemaCoordinates.latest("events.customers", "CustomerRegistered")
                : new SchemaCoordinates("events.customers", "CustomerRegistered", pinnedVersion);
        return new TypeMapping(CustomerRegistered.class, coords, SchemaType.JSON, CustomerEventRouting.ROUTING_KEY);
    }
}
