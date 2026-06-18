package com.example.contracts.orders.amqp;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Builds the single-tier TTL retry queue/binding for a base routing key (spec §9, minimal cut).
 * The retry queue holds a failed message for {@code ttlMs} then dead-letters it back to
 * events.exchange with the original routing key, so the listener retries it.
 *
 * <p>The original POC had a 3-tier 5s/30s/5m ladder; this cut keeps a single 5s tier, cycled
 * up to {@code events.retry.max-attempts} times by the consumer's DlxMessageRecoverer.
 */
public final class RetryTopologyFactory {

    private RetryTopologyFactory() {}

    /** Routing-key suffix of the single retry tier. Must match DlxMessageRecoverer.RETRY_SUFFIX. */
    public static final String RETRY_SUFFIX = "5s";

    public static Queue retryQueue(String baseRoutingKey, long ttlMs) {
        return QueueBuilder.durable(retryRoutingKey(baseRoutingKey))
                .ttl((int) ttlMs)
                .deadLetterExchange(EventExchanges.EVENTS_EXCHANGE)
                .deadLetterRoutingKey(baseRoutingKey)
                .build();
    }

    public static Binding retryBinding(Queue queue, TopicExchange retryExchange, String baseRoutingKey) {
        return BindingBuilder.bind(queue).to(retryExchange).with(retryRoutingKey(baseRoutingKey));
    }

    public static String retryRoutingKey(String baseRoutingKey) {
        return baseRoutingKey + ".retry." + RETRY_SUFFIX;
    }
}
