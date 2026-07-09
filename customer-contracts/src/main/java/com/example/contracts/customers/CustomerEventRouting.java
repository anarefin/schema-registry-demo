package com.example.contracts.customers;

/**
 * AMQP routing constants for the three customer events (spec §9/§10.4, code-first D3).
 *
 * <p>Plain {@code String} constants only — no Spring dependency — so {@code @RabbitListener}
 * and the poison demo can reference them as compile-time constants. The AMQP topology itself
 * lives in {@code schema-messaging-core} and derives queue/DLQ names from the routing key
 * ({@code rk} → main queue {@code rk.queue}, DLQ {@code rk.dlq}); these constants follow the
 * same convention and are the single source of truth for the names.
 */
public final class CustomerEventRouting {

    private CustomerEventRouting() {}

    // ---- customers.registered ----
    public static final String REGISTERED_ROUTING_KEY = "customers.registered";
    public static final String REGISTERED_QUEUE       = "customers.registered.queue";
    public static final String REGISTERED_DLQ         = "customers.registered.dlq";

    // ---- customers.address-added ----
    public static final String ADDRESS_ADDED_ROUTING_KEY = "customers.address-added";
    public static final String ADDRESS_ADDED_QUEUE       = "customers.address-added.queue";
    public static final String ADDRESS_ADDED_DLQ         = "customers.address-added.dlq";

    // ---- customers.tier-changed ----
    public static final String TIER_CHANGED_ROUTING_KEY = "customers.tier-changed";
    public static final String TIER_CHANGED_QUEUE       = "customers.tier-changed.queue";
    public static final String TIER_CHANGED_DLQ         = "customers.tier-changed.dlq";

    // ---- domain-scoped exchanges (spec contract-owned-amqp-topology D2) ----
    public static final String EXCHANGE       = "events.customers.exchange";
    public static final String DLX            = "events.customers.dlx";
    public static final String RETRY_EXCHANGE = "events.customers.retry.exchange";

    public static final String BEAN_EXCHANGE       = "customersExchange";
    public static final String BEAN_DLX            = "customersDlx";
    public static final String BEAN_RETRY_EXCHANGE = "customersRetryExchange";
}
