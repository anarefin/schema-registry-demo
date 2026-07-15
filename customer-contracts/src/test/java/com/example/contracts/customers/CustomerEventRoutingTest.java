package com.example.contracts.customers;

import com.example.amqp.topology.TopologyNaming;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test for 03-dlx-retry-exchange-naming-parity-enforced: the DLX/retry exchange names
 * declared by {@code CustomerPublisherTopology} must agree with the names
 * {@code DlxMessageRecoverer} derives at runtime via {@link TopologyNaming} from a message's
 * received exchange. Both read from {@link CustomerEventRouting}, so this fails if that class's
 * constants are ever hand-edited back to independent literals instead of staying derived.
 */
class CustomerEventRoutingTest {

    @Test
    void dlxMatchesSharedNamingConvention() {
        assertThat(CustomerEventRouting.DLX)
                .isEqualTo(TopologyNaming.dlxExchangeName(CustomerEventRouting.EXCHANGE));
    }

    @Test
    void retryExchangeMatchesSharedNamingConvention() {
        assertThat(CustomerEventRouting.RETRY_EXCHANGE)
                .isEqualTo(TopologyNaming.retryExchangeName(CustomerEventRouting.EXCHANGE));
    }
}
