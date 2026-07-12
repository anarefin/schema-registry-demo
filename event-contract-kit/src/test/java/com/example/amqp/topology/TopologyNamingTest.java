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

    @Test
    void serviceQueueNameInsertsServiceNameBeforeQueueSuffix() {
        assertThat(TopologyNaming.serviceQueueName("orders.created", "consumer-service"))
                .isEqualTo("orders.created.consumer-service.queue");
    }

    @Test
    void serviceDlqNameInsertsServiceNameBeforeDlqSuffix() {
        assertThat(TopologyNaming.serviceDlqName("orders.created", "consumer-service"))
                .isEqualTo("orders.created.consumer-service.dlq");
    }

    @Test
    void serviceDlqRoutingKeyInsertsServiceName() {
        assertThat(TopologyNaming.serviceDlqRoutingKey("orders.created", "consumer-service"))
                .isEqualTo("orders.created.consumer-service");
    }

    @Test
    void serviceRoutingKeyInsertsServiceName() {
        assertThat(TopologyNaming.serviceRoutingKey("orders.created", "consumer-service"))
                .isEqualTo("orders.created.consumer-service");
    }

    @Test
    void serviceDlqRoutingKeyMatchesServiceRoutingKey() {
        assertThat(TopologyNaming.serviceDlqRoutingKey("orders.created", "consumer-service"))
                .isEqualTo(TopologyNaming.serviceRoutingKey("orders.created", "consumer-service"));
    }

    @Test
    void serviceRetryRoutingKeyInsertsServiceNameAndTierSuffix() {
        assertThat(TopologyNaming.serviceRetryRoutingKey("orders.created", "consumer-service", 0))
                .isEqualTo("orders.created.consumer-service.retry.5s");
    }

    @Test
    void dlxExchangeNameReplacesExchangeSuffix() {
        assertThat(TopologyNaming.dlxExchangeName("events.orders.exchange")).isEqualTo("events.orders.dlx");
    }

    @Test
    void retryExchangeNameReplacesExchangeSuffix() {
        assertThat(TopologyNaming.retryExchangeName("events.orders.exchange"))
                .isEqualTo("events.orders.retry.exchange");
    }
}
