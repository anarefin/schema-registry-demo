package com.example.messaging.core.health;

import com.example.messaging.core.registry.ApicurioClient;
import com.example.messaging.core.registry.CachePreWarmer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports Apicurio Registry connectivity and pre-warm status (T-6.5, AC-6.4).
 *
 * <p>Probes the registry by attempting a known-404 fetch; a 404 response means the registry
 * is reachable. Reports pre-warm completion status from {@link CachePreWarmer}.
 * DOWN when registry is completely unreachable (connection refused / timeout).
 */
public class RegistryHealthIndicator implements HealthIndicator {

    private final ApicurioClient apicurioClient;
    private final CachePreWarmer preWarmer;

    public RegistryHealthIndicator(ApicurioClient apicurioClient, CachePreWarmer preWarmer) {
        this.apicurioClient = apicurioClient;
        this.preWarmer = preWarmer;
    }

    @Override
    public Health health() {
        try {
            boolean registryUp = probeRegistry();
            Health.Builder builder = registryUp ? Health.up() : Health.down();
            builder.withDetail("preWarmCompleted", preWarmer.isPreWarmCompleted())
                   .withDetail("preWarmErrors", preWarmer.getPreWarmErrorCount());
            return builder.build();
        } catch (Exception e) {
            return Health.down(e)
                         .withDetail("preWarmCompleted", preWarmer.isPreWarmCompleted())
                         .withDetail("preWarmErrors", preWarmer.getPreWarmErrorCount())
                         .build();
        }
    }

    private boolean probeRegistry() {
        try {
            apicurioClient.latestVersion("__health__", "__probe__");
            return true;
        } catch (com.example.messaging.core.exception.SchemaNotFoundException e) {
            return true; // 404 = registry responded = healthy
        } catch (Exception e) {
            return false;
        }
    }
}
