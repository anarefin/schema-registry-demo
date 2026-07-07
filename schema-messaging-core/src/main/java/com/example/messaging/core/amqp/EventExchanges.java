package com.example.messaging.core.amqp;

/**
 * Shared AMQP exchange names + bean names (spec §9, code-first D3). Hoisted into
 * {@code schema-messaging-core} so the topology is declared exactly once, driven by the
 * {@code TypeMappingRegistry}, instead of being duplicated per contracts module.
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
