package com.example.contracts.customers.topology;

import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerTierChanged;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Declares the {@code events.customers} domain's {@link TypeMapping} beans — one per customer
 * event, linking the Java record to its schema coordinates, schema type, and routing key. This
 * module self-activates this wiring rather than duplicating it in producer-service/consumer-service
 * (pattern established by ADR-0005); {@link TypeMapping} itself lives in {@code event-contract-kit},
 * not core, so this module needs no dependency on schema-messaging-core (ADR-0006).
 */
@AutoConfiguration
public class CustomerTypeMappingAutoConfiguration {

    private static SchemaCoordinates coords(String artifactId) {
        return new SchemaCoordinates("events.customers", artifactId);
    }

    @Bean("customerRegisteredMapping")
    @ConditionalOnMissingBean(name = "customerRegisteredMapping")
    public TypeMapping customerRegisteredMapping() {
        return new TypeMapping(CustomerRegistered.class, coords("CustomerRegistered"),
                SchemaType.JSON, CustomerEventRouting.REGISTERED_ROUTING_KEY);
    }

    @Bean("customerAddressAddedMapping")
    @ConditionalOnMissingBean(name = "customerAddressAddedMapping")
    public TypeMapping customerAddressAddedMapping() {
        return new TypeMapping(CustomerAddressAdded.class, coords("CustomerAddressAdded"),
                SchemaType.JSON, CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY);
    }

    @Bean("customerTierChangedMapping")
    @ConditionalOnMissingBean(name = "customerTierChangedMapping")
    public TypeMapping customerTierChangedMapping() {
        return new TypeMapping(CustomerTierChanged.class, coords("CustomerTierChanged"),
                SchemaType.JSON, CustomerEventRouting.TIER_CHANGED_ROUTING_KEY);
    }
}
