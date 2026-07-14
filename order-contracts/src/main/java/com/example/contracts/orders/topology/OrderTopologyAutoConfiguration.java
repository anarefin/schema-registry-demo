package com.example.contracts.orders.topology;

import com.example.amqp.topology.DomainExchanges;
import com.example.amqp.topology.DomainTopology;
import com.example.contracts.orders.OrderEventRouting;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Declares the {@code events.orders} domain's AMQP exchanges (spec contract-owned-amqp-topology
 * D2): main, DLX, and retry. Per-service queues, DLQs, and retry ladders are declared by
 * {@code ServiceQueueTopologyAutoConfiguration} in {@code schema-messaging-core} from
 * {@code @BitsEventHandler} scan — not here.
 */
@AutoConfiguration
public class OrderTopologyAutoConfiguration {

    private static final DomainExchanges EX = DomainTopology.of(OrderEventRouting.EXCHANGE);

    @Bean(OrderEventRouting.BEAN_EXCHANGE)
    @ConditionalOnMissingBean(name = OrderEventRouting.BEAN_EXCHANGE)
    public TopicExchange ordersExchange() {
        return EX.main();
    }

    @Bean(OrderEventRouting.BEAN_DLX)
    @ConditionalOnMissingBean(name = OrderEventRouting.BEAN_DLX)
    public TopicExchange ordersDlx() {
        return EX.dlx();
    }

    @Bean(OrderEventRouting.BEAN_RETRY_EXCHANGE)
    @ConditionalOnMissingBean(name = OrderEventRouting.BEAN_RETRY_EXCHANGE)
    public TopicExchange ordersRetryExchange() {
        return EX.retry();
    }
}
