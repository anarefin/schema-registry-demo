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

    public static String serviceQueueName(String routingKey, String serviceName) {
        return routingKey + "." + serviceName + QUEUE_SUFFIX;
    }

    public static String serviceDlqName(String routingKey, String serviceName) {
        return routingKey + "." + serviceName + DLQ_SUFFIX;
    }

    /**
     * The routing key a service's own main queue is privately bound to on top of the plain
     * (fan-out) routing key — used as the dead-letter target when this service's retry-tier TTL
     * expires, so redelivery lands only on this service's queue instead of fanning out to every
     * other service subscribed to the same event type.
     */
    public static String serviceRoutingKey(String routingKey, String serviceName) {
        return routingKey + "." + serviceName;
    }

    public static String serviceDlqRoutingKey(String routingKey, String serviceName) {
        return serviceRoutingKey(routingKey, serviceName);
    }

    /**
     * Undoes {@link #serviceRoutingKey}: a message that survives one retry-tier TTL expiry is
     * redelivered to the main queue via its private {@code routingKey.serviceName} binding, so
     * {@code MessageProperties.getReceivedRoutingKey()} on the *next* failure carries that suffix
     * instead of the plain routing key. Re-deriving retry/DLQ keys from the suffixed value without
     * stripping it first would append {@code serviceName} a second time. Safe to call on an
     * already-plain routing key — it's a no-op if the suffix isn't present.
     */
    public static String stripServiceRoutingKey(String routingKey, String serviceName) {
        String suffix = "." + serviceName;
        return routingKey.endsWith(suffix)
                ? routingKey.substring(0, routingKey.length() - suffix.length())
                : routingKey;
    }

    public static String serviceRetryRoutingKey(String routingKey, String serviceName, int tier) {
        return routingKey + "." + serviceName + ".retry." + tierSuffix(tier);
    }

    public static String retryRoutingKey(String baseRoutingKey, int tier) {
        return baseRoutingKey + ".retry." + tierSuffix(tier);
    }

    public static String dlxExchangeName(String mainExchangeName) {
        return mainExchangeName.replaceFirst("\\.exchange$", ".dlx");
    }

    public static String retryExchangeName(String mainExchangeName) {
        return mainExchangeName.replaceFirst("\\.exchange$", ".retry.exchange");
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
