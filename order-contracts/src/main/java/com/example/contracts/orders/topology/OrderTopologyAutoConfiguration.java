package com.example.contracts.orders.topology;

import com.example.amqp.topology.EventTopologyFactory;
import com.example.contracts.orders.OrderEventRouting;
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
 * Declares the {@code events.orders} domain's AMQP topology (spec contract-owned-amqp-topology
 * D2): the 3 domain-scoped exchanges (main, DLX, retry) plus the main queue/DLQ/retry-ladder for
 * each of the 3 order routing keys. Self-contained — enumerates its own routing keys directly
 * rather than iterating a shared registry it has no visibility into, so it carries no ordering
 * constraint relative to schema-resolution autoconfiguration.
 */
@AutoConfiguration
public class OrderTopologyAutoConfiguration {

    @Value("${events.retry.tier0.ms:5000}")   private long tier0Ms;
    @Value("${events.retry.tier1.ms:30000}")  private long tier1Ms;
    @Value("${events.retry.tier2.ms:300000}") private long tier2Ms;

    @Bean(OrderEventRouting.BEAN_EXCHANGE)
    @ConditionalOnMissingBean(name = OrderEventRouting.BEAN_EXCHANGE)
    public TopicExchange ordersExchange() {
        return new TopicExchange(OrderEventRouting.EXCHANGE, true, false);
    }

    @Bean(OrderEventRouting.BEAN_DLX)
    @ConditionalOnMissingBean(name = OrderEventRouting.BEAN_DLX)
    public TopicExchange ordersDlx() {
        return new TopicExchange(OrderEventRouting.DLX, true, false);
    }

    @Bean(OrderEventRouting.BEAN_RETRY_EXCHANGE)
    @ConditionalOnMissingBean(name = OrderEventRouting.BEAN_RETRY_EXCHANGE)
    public TopicExchange ordersRetryExchange() {
        return new TopicExchange(OrderEventRouting.RETRY_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnMissingBean(name = "orderTopologyDeclarables")
    public Declarables orderTopologyDeclarables(
            @Qualifier(OrderEventRouting.BEAN_EXCHANGE) TopicExchange ordersExchange,
            @Qualifier(OrderEventRouting.BEAN_DLX) TopicExchange ordersDlx,
            @Qualifier(OrderEventRouting.BEAN_RETRY_EXCHANGE) TopicExchange ordersRetryExchange) {

        long[] tierTtls = {tier0Ms, tier1Ms, tier2Ms};
        List<Declarable> declarables = new ArrayList<>();

        for (String routingKey : List.of(
                OrderEventRouting.CREATED_ROUTING_KEY,
                OrderEventRouting.SHIPPED_ROUTING_KEY,
                OrderEventRouting.CANCELLED_ROUTING_KEY)) {
            declarables.addAll(EventTopologyFactory.declarablesForEvent(
                    routingKey, ordersExchange, ordersDlx, ordersRetryExchange, tierTtls));
        }
        return new Declarables(declarables);
    }
}
