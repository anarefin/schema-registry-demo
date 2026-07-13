package com.example.contracts.orders;

import com.example.amqp.topology.TopologyNaming;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test for 03-dlx-retry-exchange-naming-parity-enforced: the DLX/retry exchange names
 * declared by {@code OrderTopologyAutoConfiguration} must agree with the names
 * {@code DlxMessageRecoverer} derives at runtime via {@link TopologyNaming} from a message's
 * received exchange. Both read from {@link OrderEventRouting}, so this fails if that class's
 * constants are ever hand-edited back to independent literals instead of staying derived.
 */
class OrderEventRoutingTest {

    @Test
    void dlxMatchesSharedNamingConvention() {
        assertThat(OrderEventRouting.DLX)
                .isEqualTo(TopologyNaming.dlxExchangeName(OrderEventRouting.EXCHANGE));
    }

    @Test
    void retryExchangeMatchesSharedNamingConvention() {
        assertThat(OrderEventRouting.RETRY_EXCHANGE)
                .isEqualTo(TopologyNaming.retryExchangeName(OrderEventRouting.EXCHANGE));
    }
}
