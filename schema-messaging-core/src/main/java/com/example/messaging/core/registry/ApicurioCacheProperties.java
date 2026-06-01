package com.example.messaging.core.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cache tuning for the Apicurio schema resolver (spec §10.2).
 *
 * <pre>
 * apicurio:
 *   cache:
 *     max-size: 1000
 *     ttl: PT5M
 *     refresh-after-write: PT1M
 * </pre>
 */
@ConfigurationProperties(prefix = "apicurio.cache")
public record ApicurioCacheProperties(
        long maxSize,
        Duration ttl,
        Duration refreshAfterWrite
) {

    public ApicurioCacheProperties {
        if (maxSize <= 0) maxSize = 1000;
        if (ttl == null) ttl = Duration.ofMinutes(5);
        if (refreshAfterWrite == null) refreshAfterWrite = Duration.ofMinutes(1);
    }
}
