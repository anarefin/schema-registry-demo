package com.example.amqp.topology;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainTopologyTest {

    @Test
    void ofBuildsThreeDurableNonAutoDeleteExchanges() {
        DomainExchanges exchanges = DomainTopology.of("events.orders.exchange");

        assertThat(exchanges.getMain().getName()).isEqualTo("events.orders.exchange");
        assertThat(exchanges.getDlx().getName()).isEqualTo(TopologyNaming.dlxExchangeName("events.orders.exchange"));
        assertThat(exchanges.getRetry().getName()).isEqualTo(TopologyNaming.retryExchangeName("events.orders.exchange"));

        assertThat(exchanges.getMain().isDurable()).isTrue();
        assertThat(exchanges.getDlx().isDurable()).isTrue();
        assertThat(exchanges.getRetry().isDurable()).isTrue();

        assertThat(exchanges.getMain().isAutoDelete()).isFalse();
        assertThat(exchanges.getDlx().isAutoDelete()).isFalse();
        assertThat(exchanges.getRetry().isAutoDelete()).isFalse();
    }
}
