package com.example.contracts.orders;

/**
 * AMQP routing constants for the four order events (spec §9/§10.4, code-first D3).
 *
 * <p>Plain {@code String} constants only — no Spring dependency — so the poison demo and
 * {@code OrderTypeMappingAutoConfiguration} can reference them as compile-time constants. Queue
 * names are no longer declared here — listeners resolve them internally via
 * {@code @BitsEventHandler} from the routing key below (spec
 * {@code simplified-publish-and-listen.md} D4). The AMQP topology itself lives in
 * {@code OrderTopologyAutoConfiguration} (this module) via {@code amqp-topology-kit}; these
 * constants follow the same naming convention and are the single source of truth for the names.
 */
public final class OrderEventRouting {

    private OrderEventRouting() {}

    // ---- orders.created ----
    public static final String CREATED_ROUTING_KEY = "orders.created";
    public static final String CREATED_DLQ         = "orders.created.dlq";

    // ---- orders.shipped ----
    public static final String SHIPPED_ROUTING_KEY = "orders.shipped";
    public static final String SHIPPED_DLQ         = "orders.shipped.dlq";

    // ---- orders.cancelled ----
    public static final String CANCELLED_ROUTING_KEY = "orders.cancelled";
    public static final String CANCELLED_DLQ         = "orders.cancelled.dlq";

    // ---- orders.fulfilled ----
    public static final String FULFILLED_ROUTING_KEY = "orders.fulfilled";
    public static final String FULFILLED_DLQ         = "orders.fulfilled.dlq";

    // ---- domain-scoped exchanges (spec contract-owned-amqp-topology D2) ----
    public static final String EXCHANGE       = "events.orders.exchange";
    public static final String DLX            = "events.orders.dlx";
    public static final String RETRY_EXCHANGE = "events.orders.retry.exchange";

    public static final String BEAN_EXCHANGE       = "ordersExchange";
    public static final String BEAN_DLX            = "ordersDlx";
    public static final String BEAN_RETRY_EXCHANGE = "ordersRetryExchange";
}
