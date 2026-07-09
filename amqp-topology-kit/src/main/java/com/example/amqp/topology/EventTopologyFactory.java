package com.example.amqp.topology;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the full AMQP declarable set for one event (spec contract-owned-amqp-topology D1):
 * main queue + binding, DLQ + binding, and the 3-tier TTL retry queues + bindings. Generalizes
 * the old {@code RetryTopologyFactory} by taking the domain's exchanges as parameters instead of
 * hardcoding a single shared exchange set, so each {@code *-contracts} module can declare its own
 * domain-scoped topology while reusing this logic.
 */
public final class EventTopologyFactory {

    private EventTopologyFactory() {}

    public static Queue retryQueue(String baseRoutingKey, int tier, long ttlMs, String mainExchangeName) {
        return QueueBuilder.durable(TopologyNaming.retryRoutingKey(baseRoutingKey, tier))
                .ttl((int) ttlMs)
                .deadLetterExchange(mainExchangeName)
                .deadLetterRoutingKey(baseRoutingKey)
                .build();
    }

    public static Binding retryBinding(Queue queue, TopicExchange retryExchange, String baseRoutingKey, int tier) {
        return BindingBuilder.bind(queue).to(retryExchange).with(TopologyNaming.retryRoutingKey(baseRoutingKey, tier));
    }

    public static List<Declarable> declarablesForEvent(
            String routingKey,
            TopicExchange mainExchange,
            TopicExchange dlx,
            TopicExchange retryExchange,
            long[] tierTtlsMs) {

        List<Declarable> declarables = new ArrayList<>();

        Queue mainQueue = QueueBuilder.durable(TopologyNaming.queueName(routingKey)).build();
        declarables.add(mainQueue);
        declarables.add(BindingBuilder.bind(mainQueue).to(mainExchange).with(routingKey));

        Queue dlq = QueueBuilder.durable(TopologyNaming.dlqName(routingKey)).build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(dlx).with(routingKey));

        for (int tier = 0; tier < tierTtlsMs.length; tier++) {
            Queue retryQueue = retryQueue(routingKey, tier, tierTtlsMs[tier], mainExchange.getName());
            declarables.add(retryQueue);
            declarables.add(retryBinding(retryQueue, retryExchange, routingKey, tier));
        }
        return declarables;
    }
}
