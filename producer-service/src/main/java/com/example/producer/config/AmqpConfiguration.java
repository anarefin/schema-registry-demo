package com.example.producer.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the AMQP topic exchange for the producer.
 * Consumer is responsible for declaring queues and bindings (idempotent topology per spec §9).
 */
@Configuration
public class AmqpConfiguration {

    public static final String EVENTS_EXCHANGE = "events.exchange";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
