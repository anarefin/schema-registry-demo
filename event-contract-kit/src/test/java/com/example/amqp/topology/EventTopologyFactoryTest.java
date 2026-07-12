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
    void declarablesForServiceEventProducesServiceScopedQueuesWithBindings() {
        List<Declarable> declarables = EventTopologyFactory.declarablesForEvent(
                "orders.created", "consumer-service", MAIN_EXCHANGE, DLX, RETRY_EXCHANGE, TIER_TTLS_MS);

        assertThat(declarables).hasSize(11);

        List<Queue> queues = declarables.stream().filter(Queue.class::isInstance).map(Queue.class::cast).toList();
        assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder(
                "orders.created.consumer-service.queue",
                "orders.created.consumer-service.dlq",
                "orders.created.consumer-service.retry.5s",
                "orders.created.consumer-service.retry.30s",
                "orders.created.consumer-service.retry.5m");

        // Main queue is bound twice: the plain routing key (fan-out — every subscribed service's
        // queue gets a copy of a freshly published event) and its own service-scoped routing key
        // (private — only this service's retry-tier dead-letters land here, see the test below).
        List<Binding> mainQueueBindings = declarables.stream()
                .filter(Binding.class::isInstance).map(Binding.class::cast)
                .filter(b -> b.getDestination().equals("orders.created.consumer-service.queue"))
                .toList();
        assertThat(mainQueueBindings).extracting(Binding::getRoutingKey).containsExactlyInAnyOrder(
                "orders.created", "orders.created.consumer-service");

        List<Binding> bindings = declarables.stream().filter(Binding.class::isInstance).map(Binding.class::cast).toList();
        assertThat(bindings).extracting(Binding::getRoutingKey).contains(
                "orders.created",
                "orders.created.consumer-service",
                "orders.created.consumer-service.retry.5s");
    }

    @Test
    void serviceRetryQueueDeadLettersBackToMainExchangeWithServiceScopedRoutingKey() {
        Queue retryQueue = EventTopologyFactory.serviceRetryQueue(
                "orders.created", "consumer-service", 0, 5000L, MAIN_EXCHANGE.getName());

        assertThat(retryQueue.getName()).isEqualTo("orders.created.consumer-service.retry.5s");
        assertThat(retryQueue.getArguments().get("x-dead-letter-exchange")).isEqualTo("events.orders.exchange");
        // Must be the service-scoped key, NOT the plain routing key — otherwise a retry-tier TTL
        // expiry for this service would fan out to every other service subscribed to the same
        // event on the same exchange (the bug this test guards against).
        assertThat(retryQueue.getArguments().get("x-dead-letter-routing-key"))
                .isEqualTo("orders.created.consumer-service");
    }

    @Test
    void retryTierExpiryForOneServiceNeverMatchesAnotherServicesQueueBinding() {
        List<Declarable> serviceA = EventTopologyFactory.declarablesForEvent(
                "orders.created", "service-a", MAIN_EXCHANGE, DLX, RETRY_EXCHANGE, TIER_TTLS_MS);
        List<Declarable> serviceB = EventTopologyFactory.declarablesForEvent(
                "orders.created", "service-b", MAIN_EXCHANGE, DLX, RETRY_EXCHANGE, TIER_TTLS_MS);

        Queue serviceARetryTier0 = EventTopologyFactory.serviceRetryQueue(
                "orders.created", "service-a", 0, 5000L, MAIN_EXCHANGE.getName());
        String deadLetterRoutingKey = (String) serviceARetryTier0.getArguments().get("x-dead-letter-routing-key");

        // The routing key service A's expired retry dead-letters with must match ONLY service A's
        // own main-queue binding — never any binding belonging to service B's queue.
        List<Binding> serviceBBindings = serviceB.stream()
                .filter(Binding.class::isInstance).map(Binding.class::cast).toList();
        assertThat(serviceBBindings).extracting(Binding::getRoutingKey).doesNotContain(deadLetterRoutingKey);

        List<Binding> serviceABindings = serviceA.stream()
                .filter(Binding.class::isInstance).map(Binding.class::cast)
                .filter(b -> b.getDestination().equals("orders.created.service-a.queue"))
                .toList();
        assertThat(serviceABindings).extracting(Binding::getRoutingKey).contains(deadLetterRoutingKey);
    }
}
