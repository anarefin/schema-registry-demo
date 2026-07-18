package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.RegisterEventMappings;
import com.example.amqp.topology.mapping.TypeMapping;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Registers the {@code events.customers} domain's {@link TypeMapping} beans from the build-time
 * event index — one named bean per {@code @EventMapping} record in
 * {@code com.example.contracts.customers}. This module self-activates this wiring rather than
 * duplicating it in producer-service/consumer-service (pattern established by ADR-0005);
 * {@link TypeMapping} itself lives in {@code event-contract-kit}, not core, so this module needs
 * no dependency on schema-messaging-core (ADR-0006).
 *
 * <p>Unlike the exchange beans (opt-in via {@link CustomerPublisherTopology}), these plain-data
 * mappings both roles need still auto-load — a publisher and a consumer alike must resolve Java
 * type ↔ schema coordinates. Mapping registration never declares exchanges.
 */
@AutoConfiguration
@RegisterEventMappings("com.example.contracts.customers")
public class CustomerTypeMappingAutoConfiguration {
}
