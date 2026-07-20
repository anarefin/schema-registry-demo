package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.TypeMapping;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers the {@code events.customers} domain's {@link TypeMapping} beans — one named bean per
 * {@code @EventMapping} record in {@code com.example.contracts.customers}. The beans come from
 * {@link GeneratedEventTypeMappings}, the {@code @Configuration} of explicit {@code @Bean}
 * methods emitted at this module's own {@code compile} by schema-gen-tools' {@code
 * EventMappingProcessor} (zero runtime reflection, no build-time index read). This module
 * self-activates this wiring rather than duplicating it in producer-service/consumer-service
 * (pattern established by ADR-0005); {@link TypeMapping} itself lives in {@code event-contract-kit},
 * not core, so this module needs no dependency on schema-messaging-core (ADR-0006).
 *
 * <p>Unlike the exchange beans (opt-in via {@link CustomerPublisherTopology}), these plain-data
 * mappings both roles need still auto-load — a publisher and a consumer alike must resolve Java
 * type ↔ schema coordinates. Each generated {@code @Bean} is
 * {@code @ConditionalOnMissingBean(name = "…")}, so an application bean of the same name wins.
 * Mapping registration never declares exchanges.
 */
@AutoConfiguration
@Import(GeneratedEventTypeMappings.class)
public class CustomerTypeMappingAutoConfiguration {
}
