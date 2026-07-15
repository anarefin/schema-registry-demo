package com.example.contracts.orders;

import com.example.amqp.topology.TopologyNaming;

/**
 * AMQP routing constants for the four order events (spec §9/§10.4, code-first D3).
 *
 * <p>Plain {@code String} constants only — no Spring dependency — so the poison demo and
 * {@code OrderTypeMappingAutoConfiguration} can reference them as compile-time constants. Queue
 * names are no longer declared here — listeners resolve them internally via
 * {@code @BitsEventHandler} from the routing key below (spec
 * {@code simplified-publish-and-listen.md} D4). The domain exchanges are declared by the opt-in
 * {@code OrderPublisherTopology} (this module) via {@code amqp-topology-kit}; these constants
 * follow the same naming convention and are the single source of truth for the names.
 *
 * <p>{@link #DLX} and {@link #RETRY_EXCHANGE} are derived from {@link #EXCHANGE} via
 * {@link TopologyNaming}, the same helper {@code DlxMessageRecoverer} uses at runtime to compute
 * a message's DLX/retry exchange from its received exchange — so the declared topology and the
 * failure-routing decision can never name a different exchange (see 03-dlx-retry-exchange-naming-parity-enforced).
 */
public final class OrderEventRouting {

    private OrderEventRouting() {}

    // ---- orders.created ----
    public static final String CREATED_ROUTING_KEY = "orders.created";

    // ---- orders.shipped ----
    public static final String SHIPPED_ROUTING_KEY = "orders.shipped";

    // ---- orders.cancelled ----
    public static final String CANCELLED_ROUTING_KEY = "orders.cancelled";

    // ---- orders.fulfilled ----
    public static final String FULFILLED_ROUTING_KEY = "orders.fulfilled";

    // ---- domain-scoped exchanges (spec contract-owned-amqp-topology D2) ----
    public static final String EXCHANGE       = "events.orders.exchange";
    public static final String DLX            = TopologyNaming.dlxExchangeName(EXCHANGE);
    public static final String RETRY_EXCHANGE = TopologyNaming.retryExchangeName(EXCHANGE);

    public static final String BEAN_EXCHANGE       = "ordersExchange";
    public static final String BEAN_DLX            = "ordersDlx";
    public static final String BEAN_RETRY_EXCHANGE = "ordersRetryExchange";
}
