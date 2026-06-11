package com.example.contracts.orders;

import com.example.contracts.orders.amqp.EventExchanges;
import com.example.contracts.orders.amqp.RetryTopologyFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * AMQP topology for OrderCreated events (spec §9): the 3 shared exchanges (declared
 * identically in customer-contracts; only one copy wins via @ConditionalOnMissingBean), plus
 * the orders.created main queue, DLQ, and 3-tier TTL retry queues + bindings. Any service
 * depending on order-contracts declares this topology idempotently on startup.
 */
@AutoConfiguration
public class OrderEventTopologyAutoConfiguration {

    @Value("${events.retry.tier0.ms:5000}")   private long tier0Ms;
    @Value("${events.retry.tier1.ms:30000}")  private long tier1Ms;
    @Value("${events.retry.tier2.ms:300000}") private long tier2Ms;

    // ---- Shared exchanges (also declared in customer-contracts; first one wins) ----

    @Bean(EventExchanges.BEAN_EVENTS_EXCHANGE)
    @ConditionalOnMissingBean(name = EventExchanges.BEAN_EVENTS_EXCHANGE)
    public TopicExchange eventsExchange() {
        return new TopicExchange(EventExchanges.EVENTS_EXCHANGE, true, false);
    }

    @Bean(EventExchanges.BEAN_EVENTS_DLX)
    @ConditionalOnMissingBean(name = EventExchanges.BEAN_EVENTS_DLX)
    public TopicExchange eventsDlx() {
        return new TopicExchange(EventExchanges.EVENTS_DLX, true, false);
    }

    @Bean(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE)
    @ConditionalOnMissingBean(name = EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE)
    public TopicExchange eventsRetryExchange() {
        return new TopicExchange(EventExchanges.EVENTS_RETRY_EXCHANGE, true, false);
    }

    // ---- OrderCreated topology ----

    @Bean
    @ConditionalOnMissingBean(name = "ordersCreatedQueue")
    public Queue ordersCreatedQueue() {
        return QueueBuilder.durable(OrderEventRouting.QUEUE_NAME).build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "ordersCreatedBinding")
    public Binding ordersCreatedBinding(
            @Qualifier("ordersCreatedQueue") Queue ordersCreatedQueue,
            @Qualifier(EventExchanges.BEAN_EVENTS_EXCHANGE) TopicExchange eventsExchange) {
        return BindingBuilder.bind(ordersCreatedQueue).to(eventsExchange).with(OrderEventRouting.ROUTING_KEY);
    }

    @Bean
    @ConditionalOnMissingBean(name = "ordersDlq")
    public Queue ordersDlq() {
        return QueueBuilder.durable(OrderEventRouting.DLQ_NAME).build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "ordersDlqBinding")
    public Binding ordersDlqBinding(
            @Qualifier("ordersDlq") Queue ordersDlq,
            @Qualifier(EventExchanges.BEAN_EVENTS_DLX) TopicExchange eventsDlx) {
        return BindingBuilder.bind(ordersDlq).to(eventsDlx).with(OrderEventRouting.ROUTING_KEY);
    }

    @Bean
    @ConditionalOnMissingBean(name = "ordersRetry5s")
    public Queue ordersRetry5s() { return RetryTopologyFactory.retryQueue(OrderEventRouting.ROUTING_KEY, 0, tier0Ms); }

    @Bean
    @ConditionalOnMissingBean(name = "ordersRetry30s")
    public Queue ordersRetry30s() { return RetryTopologyFactory.retryQueue(OrderEventRouting.ROUTING_KEY, 1, tier1Ms); }

    @Bean
    @ConditionalOnMissingBean(name = "ordersRetry5m")
    public Queue ordersRetry5m() { return RetryTopologyFactory.retryQueue(OrderEventRouting.ROUTING_KEY, 2, tier2Ms); }

    @Bean
    @ConditionalOnMissingBean(name = "ordersRetry5sBinding")
    public Binding ordersRetry5sBinding(
            @Qualifier("ordersRetry5s") Queue ordersRetry5s,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {
        return RetryTopologyFactory.retryBinding(ordersRetry5s, eventsRetryExchange, OrderEventRouting.ROUTING_KEY, 0);
    }

    @Bean
    @ConditionalOnMissingBean(name = "ordersRetry30sBinding")
    public Binding ordersRetry30sBinding(
            @Qualifier("ordersRetry30s") Queue ordersRetry30s,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {
        return RetryTopologyFactory.retryBinding(ordersRetry30s, eventsRetryExchange, OrderEventRouting.ROUTING_KEY, 1);
    }

    @Bean
    @ConditionalOnMissingBean(name = "ordersRetry5mBinding")
    public Binding ordersRetry5mBinding(
            @Qualifier("ordersRetry5m") Queue ordersRetry5m,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {
        return RetryTopologyFactory.retryBinding(ordersRetry5m, eventsRetryExchange, OrderEventRouting.ROUTING_KEY, 2);
    }
}
