package com.example.contracts.orders;

/**
 * AMQP routing constants for OrderCreated events (spec §9/§10.4).
 * Single source of truth for the routing key and queue/DLQ names — referenced by
 * producer-service (TypeMapping) and consumer-service (@RabbitListener).
 */
public final class OrderEventRouting {

    private OrderEventRouting() {}

    /** Routing key for events.exchange / events.dlx (dead-letter routing key) / retry-tier prefix. */
    public static final String ROUTING_KEY = "orders.created";

    /** Main queue bound to events.exchange with {@link #ROUTING_KEY}. */
    public static final String QUEUE_NAME = "orders.created.queue";

    /** Dead-letter queue bound to events.dlx with {@link #ROUTING_KEY}. */
    public static final String DLQ_NAME = "orders.created.dlq";
}
