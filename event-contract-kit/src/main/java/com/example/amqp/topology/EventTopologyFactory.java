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
 * Builds the per-service AMQP declarable set for one event (spec contract-owned-amqp-topology D1):
 * main queue + binding, DLQ + binding, and the 3-tier TTL retry queues + bindings.
 */
public final class EventTopologyFactory {

    private EventTopologyFactory() {}

    public static Queue serviceRetryQueue(
            String routingKey, String serviceName, int tier, long ttlMs, String mainExchangeName) {
        return QueueBuilder.durable(TopologyNaming.serviceRetryRoutingKey(routingKey, serviceName, tier))
                .ttl((int) ttlMs)
                .deadLetterExchange(mainExchangeName)
                .deadLetterRoutingKey(TopologyNaming.serviceRoutingKey(routingKey, serviceName))
                .build();
    }

    public static Binding serviceRetryBinding(
            Queue queue, TopicExchange retryExchange, String routingKey, String serviceName, int tier) {
        return BindingBuilder.bind(queue)
                .to(retryExchange)
                .with(TopologyNaming.serviceRetryRoutingKey(routingKey, serviceName, tier));
    }

    public static List<Declarable> declarablesForEvent(
            String routingKey,
            String serviceName,
            TopicExchange mainExchange,
            TopicExchange dlx,
            TopicExchange retryExchange,
            long[] tierTtlsMs) {

        List<Declarable> declarables = new ArrayList<>();
        String serviceRoutingKey = TopologyNaming.serviceRoutingKey(routingKey, serviceName);

        Queue mainQueue = QueueBuilder.durable(TopologyNaming.serviceQueueName(routingKey, serviceName))
                .deadLetterExchange(dlx.getName())
                .deadLetterRoutingKey(TopologyNaming.serviceDlqRoutingKey(routingKey, serviceName))
                .build();
        declarables.add(mainQueue);
        // Plain routing key: fan-out binding so every subscribed service gets its own copy of a
        // freshly published event. Service-scoped key: private binding so a retry-tier TTL expiry
        // (dead-lettered with serviceRoutingKey, see serviceRetryQueue) redelivers only to this
        // service's own queue instead of fanning out to every other subscriber again.
        declarables.add(BindingBuilder.bind(mainQueue).to(mainExchange).with(routingKey));
        declarables.add(BindingBuilder.bind(mainQueue).to(mainExchange).with(serviceRoutingKey));

        Queue dlq = QueueBuilder.durable(TopologyNaming.serviceDlqName(routingKey, serviceName)).build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(dlx).with(serviceRoutingKey));

        for (int tier = 0; tier < tierTtlsMs.length; tier++) {
            Queue retryQueue = serviceRetryQueue(routingKey, serviceName, tier, tierTtlsMs[tier], mainExchange.getName());
            declarables.add(retryQueue);
            declarables.add(serviceRetryBinding(retryQueue, retryExchange, routingKey, serviceName, tier));
        }
        return declarables;
    }
}
