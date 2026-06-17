package com.example.messaging.core.consumer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * POC-only in-memory deduplication on {@code X-Message-Id} (spec §11 / T-5.6).
 * Bounded Caffeine cache: ~10 000 entries, 1 h TTL.
 * Not suitable for multi-instance deployments without a shared store.
 *
 * <p>IDs are recorded only after successful handler completion so transient failures
 * remain eligible for the retry ladder.
 */
public class IdempotencyFilter {

    private final Cache<String, Boolean> processed;

    public IdempotencyFilter() {
        this.processed = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * Returns {@code true} if this messageId was already processed successfully.
     */
    public boolean alreadyProcessed(String messageId) {
        if (messageId == null) return false;
        return processed.getIfPresent(messageId) != null;
    }

    /**
     * Records successful processing for {@code messageId}. Call only after handler success.
     */
    public void markProcessed(String messageId) {
        if (messageId != null) {
            processed.put(messageId, Boolean.TRUE);
        }
    }
}
