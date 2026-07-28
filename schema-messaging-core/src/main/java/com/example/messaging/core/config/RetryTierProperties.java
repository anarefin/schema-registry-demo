package com.example.messaging.core.config;

import java.util.Objects;

/**
 * Single source of truth for the AMQP retry-ladder TTL tiers (spec §9/§11): three tiers,
 * bound once by {@link RetryTierPropertiesAutoConfiguration} from {@code events.retry.*} and
 * consumed identically by {@link ServiceQueueTopologyAutoConfiguration} (retry queue TTLs) and
 * {@link SchemaMessagingConsumerAutoConfiguration} (the {@code DlxMessageRecoverer}'s
 * retry-count/delay logic). Changing or adding a tier here applies to both.
 */
public final class RetryTierProperties {

    private final long tier0Ms;
    private final long tier1Ms;
    private final long tier2Ms;

    public RetryTierProperties(long tier0Ms, long tier1Ms, long tier2Ms) {
        this.tier0Ms = tier0Ms;
        this.tier1Ms = tier1Ms;
        this.tier2Ms = tier2Ms;
    }

    public long getTier0Ms() {
        return tier0Ms;
    }

    public long getTier1Ms() {
        return tier1Ms;
    }

    public long getTier2Ms() {
        return tier2Ms;
    }

    public long[] toArray() {
        return new long[] {getTier0Ms(), getTier1Ms(), getTier2Ms()};
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RetryTierProperties that)) {
            return false;
        }
        return tier0Ms == that.tier0Ms
                && tier1Ms == that.tier1Ms
                && tier2Ms == that.tier2Ms;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier0Ms, tier1Ms, tier2Ms);
    }

    @Override
    public String toString() {
        return "RetryTierProperties[tier0Ms=" + tier0Ms
                + ", tier1Ms=" + tier1Ms
                + ", tier2Ms=" + tier2Ms + "]";
    }
}
