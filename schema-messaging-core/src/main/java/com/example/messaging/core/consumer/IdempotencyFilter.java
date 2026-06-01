package com.example.messaging.core.consumer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * POC-only in-memory deduplication on {@code X-Message-Id} (spec §11 / T-5.6).
 * Bounded Caffeine cache: ~10 000 entries, 1 h TTL.
 * Not suitable for multi-instance deployments without a shared store.
 */
public class IdempotencyFilter {

    private final Cache<String, Boolean> seen;

    public IdempotencyFilter() {
        this.seen = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * Returns {@code true} if this messageId was seen before (duplicate).
     * Atomically marks the id as seen on first call.
     */
    public boolean isDuplicate(String messageId) {
        if (messageId == null) return false;
        return seen.asMap().putIfAbsent(messageId, Boolean.TRUE) != null;
    }
}
