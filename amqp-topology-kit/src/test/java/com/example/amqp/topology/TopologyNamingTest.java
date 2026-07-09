package com.example.amqp.topology;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyNamingTest {

    @Test
    void queueNameAppendsQueueSuffix() {
        assertThat(TopologyNaming.queueName("orders.created")).isEqualTo("orders.created.queue");
    }

    @Test
    void dlqNameAppendsDlqSuffix() {
        assertThat(TopologyNaming.dlqName("orders.created")).isEqualTo("orders.created.dlq");
    }

    @Test
    void retryRoutingKeyAppendsTierSuffix() {
        assertThat(TopologyNaming.retryRoutingKey("orders.created", 0)).isEqualTo("orders.created.retry.5s");
        assertThat(TopologyNaming.retryRoutingKey("orders.created", 1)).isEqualTo("orders.created.retry.30s");
        assertThat(TopologyNaming.retryRoutingKey("orders.created", 2)).isEqualTo("orders.created.retry.5m");
    }

    @Test
    void tierSuffixMapsKnownTiers() {
        assertThat(TopologyNaming.tierSuffix(0)).isEqualTo("5s");
        assertThat(TopologyNaming.tierSuffix(1)).isEqualTo("30s");
        assertThat(TopologyNaming.tierSuffix(2)).isEqualTo("5m");
    }

    @Test
    void tierSuffixFallsBackForUnknownTier() {
        assertThat(TopologyNaming.tierSuffix(3)).isEqualTo("t3");
    }
}
