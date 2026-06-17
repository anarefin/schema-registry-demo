package com.example.messaging.core.consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFilterTest {

    private final IdempotencyFilter filter = new IdempotencyFilter();

    @Test
    void failedAttemptDoesNotBlockRetry() {
        String messageId = "msg-1";

        assertThat(filter.alreadyProcessed(messageId)).isFalse();
        // handler throws — markProcessed not called
        assertThat(filter.alreadyProcessed(messageId)).isFalse();
    }

    @Test
    void successfulProcessingBlocksDuplicate() {
        String messageId = "msg-2";

        filter.markProcessed(messageId);

        assertThat(filter.alreadyProcessed(messageId)).isTrue();
    }

    @Test
    void nullMessageIdIsIgnored() {
        assertThat(filter.alreadyProcessed(null)).isFalse();
        filter.markProcessed(null);
        assertThat(filter.alreadyProcessed(null)).isFalse();
    }
}
