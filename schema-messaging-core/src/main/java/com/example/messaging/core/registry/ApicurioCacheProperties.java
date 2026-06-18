package com.example.messaging.core.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable settings for the {@link SchemaResolver} Caffeine caches (prefix {@code apicurio.cache}).
 *
 * <ul>
 *   <li>{@code maxSize} — upper bound on cached entries (both caches), so the cache can never
 *       grow without limit.</li>
 *   <li>{@code ttl} — {@code expireAfterWrite} for the <b>mutable</b> coordinate cache, so a
 *       {@code latest} coordinate eventually re-resolves and picks up newly registered versions.
 *       (The globalId cache is immutable and is not TTL-bounded.)</li>
 *   <li>{@code refreshAfterWrite} — async stale-while-revalidate window for the coordinate cache;
 *       must be shorter than {@code ttl}. Reads after this window return the current value
 *       immediately and trigger a background reload.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "apicurio.cache")
public class ApicurioCacheProperties {

    private long maxSize = 1000;
    private Duration ttl = Duration.ofMinutes(5);
    private Duration refreshAfterWrite = Duration.ofMinutes(1);

    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getRefreshAfterWrite() {
        return refreshAfterWrite;
    }

    public void setRefreshAfterWrite(Duration refreshAfterWrite) {
        this.refreshAfterWrite = refreshAfterWrite;
    }
}
