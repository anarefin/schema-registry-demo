package com.example.contracts.customers.topology;

import com.example.amqp.topology.EventTopologyFactory;
import com.example.contracts.customers.CustomerEventRouting;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares the {@code events.customers} domain's AMQP topology (spec contract-owned-amqp-topology
 * D2): the 3 domain-scoped exchanges (main, DLX, retry) plus the main queue/DLQ/retry-ladder for
 * each of the 3 customer routing keys. Self-contained — enumerates its own routing keys directly
 * rather than iterating a shared registry it has no visibility into, so it carries no ordering
 * constraint relative to schema-resolution autoconfiguration.
 */
@AutoConfiguration
public class CustomerTopologyAutoConfiguration {

    @Value("${events.retry.tier0.ms:5000}")   private long tier0Ms;
    @Value("${events.retry.tier1.ms:30000}")  private long tier1Ms;
    @Value("${events.retry.tier2.ms:300000}") private long tier2Ms;

    @Bean(CustomerEventRouting.BEAN_EXCHANGE)
    @ConditionalOnMissingBean(name = CustomerEventRouting.BEAN_EXCHANGE)
    public TopicExchange customersExchange() {
        return new TopicExchange(CustomerEventRouting.EXCHANGE, true, false);
    }

    @Bean(CustomerEventRouting.BEAN_DLX)
    @ConditionalOnMissingBean(name = CustomerEventRouting.BEAN_DLX)
    public TopicExchange customersDlx() {
        return new TopicExchange(CustomerEventRouting.DLX, true, false);
    }

    @Bean(CustomerEventRouting.BEAN_RETRY_EXCHANGE)
    @ConditionalOnMissingBean(name = CustomerEventRouting.BEAN_RETRY_EXCHANGE)
    public TopicExchange customersRetryExchange() {
        return new TopicExchange(CustomerEventRouting.RETRY_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnMissingBean(name = "customerTopologyDeclarables")
    public Declarables customerTopologyDeclarables(
            @Qualifier(CustomerEventRouting.BEAN_EXCHANGE) TopicExchange customersExchange,
            @Qualifier(CustomerEventRouting.BEAN_DLX) TopicExchange customersDlx,
            @Qualifier(CustomerEventRouting.BEAN_RETRY_EXCHANGE) TopicExchange customersRetryExchange) {

        long[] tierTtls = {tier0Ms, tier1Ms, tier2Ms};
        List<Declarable> declarables = new ArrayList<>();

        for (String routingKey : List.of(
                CustomerEventRouting.REGISTERED_ROUTING_KEY,
                CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY,
                CustomerEventRouting.TIER_CHANGED_ROUTING_KEY)) {
            declarables.addAll(EventTopologyFactory.declarablesForEvent(
                    routingKey, customersExchange, customersDlx, customersRetryExchange, tierTtls));
        }
        return new Declarables(declarables);
    }
}
