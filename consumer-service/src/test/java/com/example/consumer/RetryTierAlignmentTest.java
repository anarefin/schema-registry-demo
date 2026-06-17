package com.example.consumer;

import com.example.messaging.core.consumer.RetryTierSuffixes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards retry routing-key suffix alignment between core recoverer and contract topology.
 */
class RetryTierAlignmentTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void orderContractsTierSuffixMatchesCore(int tier) {
        assertThat(com.example.contracts.orders.amqp.RetryTopologyFactory.tierSuffix(tier))
                .isEqualTo(RetryTierSuffixes.suffix(tier));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void customerContractsTierSuffixMatchesCore(int tier) {
        assertThat(com.example.contracts.customers.amqp.RetryTopologyFactory.tierSuffix(tier))
                .isEqualTo(RetryTierSuffixes.suffix(tier));
    }
}
