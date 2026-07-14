package com.example.amqp.topology;

import org.springframework.amqp.core.TopicExchange;

/**
 * Builds the three domain-scoped {@link TopicExchange}s (main, DLX, retry) from a single main
 * exchange name. DLX and retry names are derived via {@link TopologyNaming} — the same helpers
 * {@code DlxMessageRecoverer} uses at runtime, so declared topology and failure routing cannot
 * drift.
 */
public final class DomainTopology {

    private DomainTopology() {}

    public static DomainExchanges of(String mainExchangeName) {
        return new DomainExchanges(
                new TopicExchange(mainExchangeName, true, false),
                new TopicExchange(TopologyNaming.dlxExchangeName(mainExchangeName), true, false),
                new TopicExchange(TopologyNaming.retryExchangeName(mainExchangeName), true, false));
    }
}
