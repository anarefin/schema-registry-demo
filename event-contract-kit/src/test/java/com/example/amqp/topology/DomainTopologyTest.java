package com.example.amqp.topology;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainTopologyTest {

    @Test
    void ofBuildsThreeDurableNonAutoDeleteExchanges() {
        DomainExchanges exchanges = DomainTopology.of("events.orders.exchange");

        assertThat(exchanges.main().getName()).isEqualTo("events.orders.exchange");
        assertThat(exchanges.dlx().getName()).isEqualTo(TopologyNaming.dlxExchangeName("events.orders.exchange"));
        assertThat(exchanges.retry().getName()).isEqualTo(TopologyNaming.retryExchangeName("events.orders.exchange"));

        assertThat(exchanges.main().isDurable()).isTrue();
        assertThat(exchanges.dlx().isDurable()).isTrue();
        assertThat(exchanges.retry().isDurable()).isTrue();

        assertThat(exchanges.main().isAutoDelete()).isFalse();
        assertThat(exchanges.dlx().isAutoDelete()).isFalse();
        assertThat(exchanges.retry().isAutoDelete()).isFalse();
    }
}
