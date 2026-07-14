package com.example.amqp.topology;

import org.springframework.amqp.core.TopicExchange;

/**
 * The three domain-scoped AMQP exchanges (main, DLX, retry) built by {@link DomainTopology}.
 */
public record DomainExchanges(TopicExchange main, TopicExchange dlx, TopicExchange retry) {}
