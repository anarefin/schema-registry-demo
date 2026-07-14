package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.Mappings;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerTierChanged;
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

    private static final Mappings M = Mappings.forDomain("events.customers", CustomerEventRouting.EXCHANGE);

    @Bean("customerRegisteredMapping")
    @ConditionalOnMissingBean(name = "customerRegisteredMapping")
    public TypeMapping customerRegisteredMapping() {
        return M.json(CustomerRegistered.class, CustomerEventRouting.REGISTERED_ROUTING_KEY);
    }

    @Bean("customerAddressAddedMapping")
    @ConditionalOnMissingBean(name = "customerAddressAddedMapping")
    public TypeMapping customerAddressAddedMapping() {
        return M.json(CustomerAddressAdded.class, CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY);
    }

    @Bean("customerTierChangedMapping")
    @ConditionalOnMissingBean(name = "customerTierChangedMapping")
    public TypeMapping customerTierChangedMapping() {
        return M.json(CustomerTierChanged.class, CustomerEventRouting.TIER_CHANGED_ROUTING_KEY);
    }
}
