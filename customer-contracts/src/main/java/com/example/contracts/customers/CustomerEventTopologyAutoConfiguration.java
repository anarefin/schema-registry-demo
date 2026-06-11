package com.example.contracts.customers;

import com.example.contracts.customers.amqp.EventExchanges;
import com.example.contracts.customers.amqp.RetryTopologyFactory;
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
 * AMQP topology for CustomerRegistered events (spec §9): the 3 shared exchanges (declared
 * identically in order-contracts; only one copy wins via @ConditionalOnMissingBean), plus
 * the customers.registered main queue, DLQ, and 3-tier TTL retry queues + bindings. Any
 * service depending on customer-contracts declares this topology idempotently on startup.
 */
@AutoConfiguration
public class CustomerEventTopologyAutoConfiguration {

    @Value("${events.retry.tier0.ms:5000}")   private long tier0Ms;
    @Value("${events.retry.tier1.ms:30000}")  private long tier1Ms;
    @Value("${events.retry.tier2.ms:300000}") private long tier2Ms;

    // ---- Shared exchanges (also declared in order-contracts; first one wins) ----

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

    // ---- CustomerRegistered topology ----

    @Bean
    @ConditionalOnMissingBean(name = "customersRegisteredQueue")
    public Queue customersRegisteredQueue() {
        return QueueBuilder.durable(CustomerEventRouting.QUEUE_NAME).build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "customersRegisteredBinding")
    public Binding customersRegisteredBinding(
            @Qualifier("customersRegisteredQueue") Queue customersRegisteredQueue,
            @Qualifier(EventExchanges.BEAN_EVENTS_EXCHANGE) TopicExchange eventsExchange) {
        return BindingBuilder.bind(customersRegisteredQueue).to(eventsExchange).with(CustomerEventRouting.ROUTING_KEY);
    }

    @Bean
    @ConditionalOnMissingBean(name = "customersDlq")
    public Queue customersDlq() {
        return QueueBuilder.durable(CustomerEventRouting.DLQ_NAME).build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "customersDlqBinding")
    public Binding customersDlqBinding(
            @Qualifier("customersDlq") Queue customersDlq,
            @Qualifier(EventExchanges.BEAN_EVENTS_DLX) TopicExchange eventsDlx) {
        return BindingBuilder.bind(customersDlq).to(eventsDlx).with(CustomerEventRouting.ROUTING_KEY);
    }

    @Bean
    @ConditionalOnMissingBean(name = "customersRetry5s")
    public Queue customersRetry5s() { return RetryTopologyFactory.retryQueue(CustomerEventRouting.ROUTING_KEY, 0, tier0Ms); }

    @Bean
    @ConditionalOnMissingBean(name = "customersRetry30s")
    public Queue customersRetry30s() { return RetryTopologyFactory.retryQueue(CustomerEventRouting.ROUTING_KEY, 1, tier1Ms); }

    @Bean
    @ConditionalOnMissingBean(name = "customersRetry5m")
    public Queue customersRetry5m() { return RetryTopologyFactory.retryQueue(CustomerEventRouting.ROUTING_KEY, 2, tier2Ms); }

    @Bean
    @ConditionalOnMissingBean(name = "customersRetry5sBinding")
    public Binding customersRetry5sBinding(
            @Qualifier("customersRetry5s") Queue customersRetry5s,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {
        return RetryTopologyFactory.retryBinding(customersRetry5s, eventsRetryExchange, CustomerEventRouting.ROUTING_KEY, 0);
    }

    @Bean
    @ConditionalOnMissingBean(name = "customersRetry30sBinding")
    public Binding customersRetry30sBinding(
            @Qualifier("customersRetry30s") Queue customersRetry30s,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {
        return RetryTopologyFactory.retryBinding(customersRetry30s, eventsRetryExchange, CustomerEventRouting.ROUTING_KEY, 1);
    }

    @Bean
    @ConditionalOnMissingBean(name = "customersRetry5mBinding")
    public Binding customersRetry5mBinding(
            @Qualifier("customersRetry5m") Queue customersRetry5m,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {
        return RetryTopologyFactory.retryBinding(customersRetry5m, eventsRetryExchange, CustomerEventRouting.ROUTING_KEY, 2);
    }
}
