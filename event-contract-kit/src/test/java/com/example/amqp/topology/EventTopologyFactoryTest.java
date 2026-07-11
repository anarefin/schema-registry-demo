package com.example.amqp.topology;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventTopologyFactoryTest {

    private static final TopicExchange MAIN_EXCHANGE = new TopicExchange("events.orders.exchange", true, false);
    private static final TopicExchange DLX = new TopicExchange("events.orders.dlx", true, false);
    private static final TopicExchange RETRY_EXCHANGE = new TopicExchange("events.orders.retry.exchange", true, false);
    private static final long[] TIER_TTLS_MS = {5000L, 30000L, 300000L};

    @Test
    void declarablesForEventProducesMainDlqAndRetryQueuesWithBindings() {
        List<Declarable> declarables =
                EventTopologyFactory.declarablesForEvent("orders.created", MAIN_EXCHANGE, DLX, RETRY_EXCHANGE, TIER_TTLS_MS);

        // main queue + binding, DLQ + binding, 3 retry queues + bindings = 10 declarables
        assertThat(declarables).hasSize(10);

        List<Queue> queues = declarables.stream().filter(Queue.class::isInstance).map(Queue.class::cast).toList();
        assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder(
                "orders.created.queue",
                "orders.created.dlq",
                "orders.created.retry.5s",
                "orders.created.retry.30s",
                "orders.created.retry.5m");

        List<Binding> bindings = declarables.stream().filter(Binding.class::isInstance).map(Binding.class::cast).toList();
        assertThat(bindings).hasSize(5);
        assertThat(bindings).extracting(Binding::getExchange).containsOnly(
                "events.orders.exchange", "events.orders.dlx", "events.orders.retry.exchange");
    }

    @Test
    void retryQueueDeadLettersBackToMainExchangeWithBaseRoutingKey() {
        Queue retryQueue = EventTopologyFactory.retryQueue("orders.created", 0, 5000L, MAIN_EXCHANGE.getName());

        assertThat(retryQueue.getName()).isEqualTo("orders.created.retry.5s");
        assertThat(retryQueue.getArguments().get("x-message-ttl")).isEqualTo(5000);
        assertThat(retryQueue.getArguments().get("x-dead-letter-exchange")).isEqualTo("events.orders.exchange");
        assertThat(retryQueue.getArguments().get("x-dead-letter-routing-key")).isEqualTo("orders.created");
    }

    @Test
    void retryBindingBindsQueueToRetryExchangeWithTierRoutingKey() {
        Queue retryQueue = EventTopologyFactory.retryQueue("orders.created", 1, 30000L, MAIN_EXCHANGE.getName());
        Binding binding = EventTopologyFactory.retryBinding(retryQueue, RETRY_EXCHANGE, "orders.created", 1);

        assertThat(binding.getExchange()).isEqualTo("events.orders.retry.exchange");
        assertThat(binding.getRoutingKey()).isEqualTo("orders.created.retry.30s");
    }
}
