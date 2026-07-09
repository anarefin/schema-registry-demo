package com.example.amqp.topology;

/**
 * Naming conventions shared by every domain's AMQP topology (spec contract-owned-amqp-topology
 * D1): queue/DLQ names derived from a routing key, and the 3-tier TTL retry-ladder suffixes
 * (tier 0 = 5s, tier 1 = 30s, tier 2 = 5m).
 */
public final class TopologyNaming {

    private TopologyNaming() {}

    public static final String QUEUE_SUFFIX = ".queue";
    public static final String DLQ_SUFFIX = ".dlq";

    public static String queueName(String routingKey) {
        return routingKey + QUEUE_SUFFIX;
    }

    public static String dlqName(String routingKey) {
        return routingKey + DLQ_SUFFIX;
    }

    public static String retryRoutingKey(String baseRoutingKey, int tier) {
        return baseRoutingKey + ".retry." + tierSuffix(tier);
    }

    public static String tierSuffix(int tier) {
        return switch (tier) {
            case 0 -> "5s";
            case 1 -> "30s";
            case 2 -> "5m";
            default -> "t" + tier;
        };
    }
}
