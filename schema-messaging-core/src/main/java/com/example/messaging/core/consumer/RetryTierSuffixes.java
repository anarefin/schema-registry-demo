package com.example.messaging.core.consumer;

/**
 * Retry routing-key tier suffixes (spec §9). Must stay aligned with
 * {@code RetryTopologyFactory.tierSuffix} in order-contracts and customer-contracts.
 */
public final class RetryTierSuffixes {

    private RetryTierSuffixes() {}

    public static String suffix(int tier) {
        return switch (tier) {
            case 0 -> "5s";
            case 1 -> "30s";
            case 2 -> "5m";
            default -> "t" + tier;
        };
    }
}
