package com.example.contracts.orders.topology;

import com.example.amqp.topology.DomainExchanges;
import com.example.amqp.topology.DomainTopology;
import com.example.contracts.orders.OrderEventRouting;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the {@code events.orders} domain's AMQP exchanges (main, DLX, retry) — the publisher's
 * half of the split topology.
 *
 * <p><strong>Opt-in, not auto-configured.</strong> Exchange ownership follows domain cardinality:
 * only the single service that <em>publishes</em> the orders domain declares its exchanges. That
 * service {@code @Import}s this class explicitly; a contracts jar on the classpath no longer forces
 * any service to declare exchanges (this class is deliberately absent from
 * {@code AutoConfiguration.imports}). Consumers declare only their private queues and bind to these
 * publisher-owned exchanges — per-service queues/DLQs/retry ladders are declared by
 * {@code ServiceQueueTopologyAutoConfiguration} in {@code schema-messaging-core} from the
 * {@code @BitsEventHandler} scan, not here.
 *
 * <p>{@code TypeMapping} beans (the plain-data mapping both roles need) still auto-load via
 * {@code OrderTypeMappingAutoConfiguration}.
 */
@Configuration
public class OrderPublisherTopology {

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
