package com.example.contracts.orders.amqp;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Builds the 3-tier TTL retry queue/binding pair for a given base routing key (spec §9).
 * Tier 0 = 5s, tier 1 = 30s, tier 2 = 5m. Each retry queue dead-letters back to
 * events.exchange with the original routing key, and is bound to events.retry.exchange.
 */
public final class RetryTopologyFactory {

    private RetryTopologyFactory() {}

    public static Queue retryQueue(String baseRoutingKey, int tier, long ttlMs) {
        return QueueBuilder.durable(retryRoutingKey(baseRoutingKey, tier))
                .ttl((int) ttlMs)
                .deadLetterExchange(EventExchanges.EVENTS_EXCHANGE)
                .deadLetterRoutingKey(baseRoutingKey)
                .build();
    }

    public static Binding retryBinding(Queue queue, TopicExchange retryExchange, String baseRoutingKey, int tier) {
        return BindingBuilder.bind(queue).to(retryExchange).with(retryRoutingKey(baseRoutingKey, tier));
    }

    public static String retryRoutingKey(String baseRoutingKey, int tier) {
        return baseRoutingKey + ".retry." + tierSuffix(tier);
    }

    public static String tierSuffix(int tier) {
        return switch (tier) {
            case 0 -> "5s";
            case 1 -> "30s";
            case 2 -> "5m";
            default -> "t" + tier;
        };
    }
}
