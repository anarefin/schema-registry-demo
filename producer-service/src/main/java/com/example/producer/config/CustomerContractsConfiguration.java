package com.example.producer.config;

import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerTierChanged;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-3.3: one {@link TypeMapping} bean per customer event (JSON Schema, spec §10.3/§10.4, code-first
 * D3). Version pinning: set {@code schema.customers.pinned-version} in application.yml to lock all
 * customer artifacts to a registered version; leave blank to always resolve the latest (spec §10.5).
 */
@Configuration
public class CustomerContractsConfiguration {

    @Value("${schema.customers.pinned-version:}")
    private String pinnedVersion;

    private SchemaCoordinates coords(String artifactId) {
        return (pinnedVersion == null || pinnedVersion.isBlank())
                ? SchemaCoordinates.latest("events.customers", artifactId)
                : new SchemaCoordinates("events.customers", artifactId, pinnedVersion);
    }

    @Bean
    public TypeMapping customerRegisteredMapping() {
        return new TypeMapping(CustomerRegistered.class, coords("CustomerRegistered"),
                SchemaType.JSON, CustomerEventRouting.REGISTERED_ROUTING_KEY);
    }

    @Bean
    public TypeMapping customerAddressAddedMapping() {
        return new TypeMapping(CustomerAddressAdded.class, coords("CustomerAddressAdded"),
                SchemaType.JSON, CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY);
    }

    @Bean
    public TypeMapping customerTierChangedMapping() {
        return new TypeMapping(CustomerTierChanged.class, coords("CustomerTierChanged"),
                SchemaType.JSON, CustomerEventRouting.TIER_CHANGED_ROUTING_KEY);
    }
}
