package com.example.contracts.orders.amqp;

/**
 * Shared AMQP exchange names + bean names (spec §9). The name-based
 * {@code @ConditionalOnMissingBean} in OrderEventTopologyAutoConfiguration declares each
 * exchange idempotently.
 */
public final class EventExchanges {

    private EventExchanges() {}

    public static final String EVENTS_EXCHANGE       = "events.exchange";
    public static final String EVENTS_DLX            = "events.dlx";
    public static final String EVENTS_RETRY_EXCHANGE = "events.retry.exchange";

    public static final String BEAN_EVENTS_EXCHANGE       = "eventsExchange";
    public static final String BEAN_EVENTS_DLX            = "eventsDlx";
    public static final String BEAN_EVENTS_RETRY_EXCHANGE = "eventsRetryExchange";
}
