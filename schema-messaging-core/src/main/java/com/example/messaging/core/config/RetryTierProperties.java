package com.example.messaging.core.config;

/**
 * Single source of truth for the AMQP retry-ladder TTL tiers (spec §9/§11): three tiers,
 * bound once by {@link RetryTierPropertiesAutoConfiguration} from {@code events.retry.*} and
 * consumed identically by {@link ServiceQueueTopologyAutoConfiguration} (retry queue TTLs) and
 * {@link SchemaMessagingConsumerAutoConfiguration} (the {@code DlxMessageRecoverer}'s
 * retry-count/delay logic). Changing or adding a tier here applies to both.
 */
public record RetryTierProperties(long tier0Ms, long tier1Ms, long tier2Ms) {

    public long[] toArray() {
        return new long[] {tier0Ms, tier1Ms, tier2Ms};
    }
}
