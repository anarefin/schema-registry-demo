package com.example.contracts.customers.topology;

import com.example.contracts.customers.CustomerEventRouting;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Declares the {@code events.customers} domain's AMQP exchanges (spec contract-owned-amqp-topology
 * D2): main, DLX, and retry. Per-service queues, DLQs, and retry ladders are declared by
 * {@code ServiceQueueTopologyAutoConfiguration} in {@code schema-messaging-core} from
 * {@code @BitsEventHandler} scan — not here.
 */
@AutoConfiguration
public class CustomerTopologyAutoConfiguration {

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
}
