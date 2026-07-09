package com.example.contracts.orders;

/**
 * AMQP routing constants for the three order events (spec §9/§10.4, code-first D3).
 *
 * <p>Plain {@code String} constants only — no Spring dependency — so {@code @RabbitListener}
 * and the poison demo can reference them as compile-time constants. The AMQP topology itself
 * lives in {@code schema-messaging-core} and derives queue/DLQ names from the routing key
 * ({@code rk} → main queue {@code rk.queue}, DLQ {@code rk.dlq}); these constants follow the
 * same convention and are the single source of truth for the names.
 */
public final class OrderEventRouting {

    private OrderEventRouting() {}

    // ---- orders.created ----
    public static final String CREATED_ROUTING_KEY = "orders.created";
    public static final String CREATED_QUEUE       = "orders.created.queue";
    public static final String CREATED_DLQ         = "orders.created.dlq";

    // ---- orders.shipped ----
    public static final String SHIPPED_ROUTING_KEY = "orders.shipped";
    public static final String SHIPPED_QUEUE       = "orders.shipped.queue";
    public static final String SHIPPED_DLQ         = "orders.shipped.dlq";

    // ---- orders.cancelled ----
    public static final String CANCELLED_ROUTING_KEY = "orders.cancelled";
    public static final String CANCELLED_QUEUE       = "orders.cancelled.queue";
    public static final String CANCELLED_DLQ         = "orders.cancelled.dlq";

    // ---- domain-scoped exchanges (spec contract-owned-amqp-topology D2) ----
    public static final String EXCHANGE       = "events.orders.exchange";
    public static final String DLX            = "events.orders.dlx";
    public static final String RETRY_EXCHANGE = "events.orders.retry.exchange";

    public static final String BEAN_EXCHANGE       = "ordersExchange";
    public static final String BEAN_DLX            = "ordersDlx";
    public static final String BEAN_RETRY_EXCHANGE = "ordersRetryExchange";
}
