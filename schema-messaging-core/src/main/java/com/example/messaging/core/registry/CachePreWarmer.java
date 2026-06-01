package com.example.messaging.core.registry;

import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-warms {@link SchemaResolver} on startup for every registered {@link TypeMapping} (T-1.4).
 * Failures are caught and logged as WARN — the context still starts (TC-1.6).
 * Exposes pre-warm status for {@link com.example.messaging.core.health.RegistryHealthIndicator}.
 */
public class CachePreWarmer {

    private static final Logger log = LoggerFactory.getLogger(CachePreWarmer.class);

    private final SchemaResolver schemaResolver;
    private final TypeMappingRegistry typeMappingRegistry;
    private final AtomicBoolean preWarmCompleted = new AtomicBoolean(false);
    private final AtomicInteger preWarmErrors = new AtomicInteger(0);

    public CachePreWarmer(SchemaResolver schemaResolver, TypeMappingRegistry typeMappingRegistry) {
        this.schemaResolver = schemaResolver;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preWarmAll() {
        log.info("Pre-warming schema cache for {} type mappings", typeMappingRegistry.all().size());
        preWarmErrors.set(0);
        for (TypeMapping mapping : typeMappingRegistry.all()) {
            try {
                schemaResolver.preWarm(mapping.coordinates());
            } catch (Exception e) {
                preWarmErrors.incrementAndGet();
                log.warn("Pre-warm error for {} — continuing", mapping.coordinates(), e);
            }
        }
        preWarmCompleted.set(true);
        log.info("Schema cache pre-warm complete ({} errors)", preWarmErrors.get());
    }

    public boolean isPreWarmCompleted() { return preWarmCompleted.get(); }
    public int getPreWarmErrorCount()   { return preWarmErrors.get(); }
}
