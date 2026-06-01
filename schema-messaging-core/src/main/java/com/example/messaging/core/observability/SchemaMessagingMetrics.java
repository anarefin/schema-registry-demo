package com.example.messaging.core.observability;

import com.example.messaging.core.model.SchemaType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers and manages all spec §15 Micrometer meters for schema messaging.
 * No-op safe when {@link MeterRegistry} is absent (unit test context).
 *
 * <p>Metrics registered:
 * <ul>
 *   <li>{@code schema.cache.hits} / {@code schema.cache.misses} — by schemaType</li>
 *   <li>{@code schema.fetch.duration} — Timer for registry fetch latency</li>
 *   <li>{@code schema.fetch.failures} — Counter for registry fetch failures</li>
 *   <li>{@code schema.publish.count} / {@code schema.publish.failures} — by schemaType</li>
 *   <li>{@code schema.consume.count} / {@code schema.consume.failures} — by schemaType</li>
 *   <li>{@code schema.validation.failures} — Counter</li>
 *   <li>{@code schema.dlq.depth} — gauge placeholder (actual gauge wired in Phase 5)</li>
 * </ul>
 */
public class SchemaMessagingMetrics implements MeterBinder {

    private MeterRegistry registry;

    // Per-type counters (lazily created)
    private final ConcurrentHashMap<String, Counter> publishCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> publishFailures  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> consumeCounters  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> consumeFailures  = new ConcurrentHashMap<>();

    private Counter cacheHits;
    private Counter cacheMisses;
    private Counter fetchFailures;
    private Counter validationFailures;
    private Timer   fetchDuration;

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        cacheHits          = Counter.builder("schema.cache.hits").description("Schema cache hits").register(registry);
        cacheMisses        = Counter.builder("schema.cache.misses").description("Schema cache misses").register(registry);
        fetchFailures      = Counter.builder("schema.fetch.failures").description("Registry fetch failures").register(registry);
        validationFailures = Counter.builder("schema.validation.failures").description("Schema validation failures").register(registry);
        fetchDuration      = Timer.builder("schema.fetch.duration").description("Registry fetch latency").register(registry);
    }

    // ---- cache -------------------------------------------------------------

    public void recordCacheHit()  { if (cacheHits != null)   cacheHits.increment(); }
    public void recordCacheMiss() { if (cacheMisses != null) cacheMisses.increment(); }

    // ---- fetch -------------------------------------------------------------

    public Timer.Sample startFetchSample() {
        return registry != null ? Timer.start(registry) : null;
    }

    public void stopFetchSample(Timer.Sample sample) {
        if (sample != null && fetchDuration != null) sample.stop(fetchDuration);
    }

    public void recordFetchFailure() { if (fetchFailures != null) fetchFailures.increment(); }

    // ---- publish / consume -------------------------------------------------

    public void recordPublish(SchemaType type) {
        if (registry == null) return;
        publishCounters.computeIfAbsent(type.name(),
                t -> Counter.builder("schema.publish.count").tag("type", t).register(registry)).increment();
    }

    public void recordPublishFailure(SchemaType type) {
        if (registry == null) return;
        publishFailures.computeIfAbsent(type.name(),
                t -> Counter.builder("schema.publish.failures").tag("type", t).register(registry)).increment();
    }

    public void recordConsume(SchemaType type) {
        if (registry == null) return;
        consumeCounters.computeIfAbsent(type.name(),
                t -> Counter.builder("schema.consume.count").tag("type", t).register(registry)).increment();
    }

    public void recordConsumeFailure(SchemaType type) {
        if (registry == null) return;
        consumeFailures.computeIfAbsent(type.name(),
                t -> Counter.builder("schema.consume.failures").tag("type", t).register(registry)).increment();
    }

    public void recordValidationFailure() { if (validationFailures != null) validationFailures.increment(); }
}
