package com.example.messaging.core.registry;

import com.example.messaging.core.exception.RegistryUnavailableException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Caffeine-backed {@link SchemaResolver}: cache hit (single fetch), globalId
 * cross-population, serve-last-known-good on registry outage past TTL, and outage with an empty
 * cache propagating {@link RegistryUnavailableException}.
 */
class SchemaResolverTest {

    private static final SchemaCoordinates COORDS =
            SchemaCoordinates.latest("events.orders", "OrderCreated");
    private static final long GLOBAL_ID = 7L;
    private static final ResolvedSchema SCHEMA =
            new ResolvedSchema(GLOBAL_ID, SchemaType.JSON, "{}".getBytes());

    private final ApicurioClient client = mock(ApicurioClient.class);

    /** A coordinate is fetched once; subsequent reads are served from the cache. */
    @Test
    void coordinateCacheHit_fetchesOnce() {
        when(client.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);
        SchemaResolver resolver = new SchemaResolver(client, new ApicurioCacheProperties());

        assertThat(resolver.resolveByCoordinates(COORDS)).isSameAs(SCHEMA);
        assertThat(resolver.resolveByCoordinates(COORDS)).isSameAs(SCHEMA);

        verify(client, times(1)).fetchByCoordinates(COORDS);
    }

    /** A coordinate fetch cross-populates the globalId cache, so resolveByGlobalId is a hit. */
    @Test
    void coordinateFetch_crossPopulatesGlobalIdCache() {
        when(client.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);
        SchemaResolver resolver = new SchemaResolver(client, new ApicurioCacheProperties());

        resolver.resolveByCoordinates(COORDS);
        ResolvedSchema byId = resolver.resolveByGlobalId(GLOBAL_ID, SchemaType.JSON);

        assertThat(byId).isSameAs(SCHEMA);
        verify(client, never()).fetchByGlobalId(anyLong(), any());
    }

    /** After TTL expiry, if the registry is down the resolver serves the last-known-good value. */
    @Test
    void outageAfterTtlExpiry_servesLastKnownGood() {
        FakeTicker ticker = new FakeTicker();
        SchemaResolver resolver = new SchemaResolver(client, new ApicurioCacheProperties(), ticker);

        when(client.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);
        assertThat(resolver.resolveByCoordinates(COORDS)).isSameAs(SCHEMA);

        // Past the default 5m TTL → entry expired; registry now unreachable.
        ticker.advance(Duration.ofMinutes(6));
        when(client.fetchByCoordinates(COORDS))
                .thenThrow(new RegistryUnavailableException("down", new RuntimeException()));

        assertThat(resolver.resolveByCoordinates(COORDS)).isSameAs(SCHEMA); // stale, not thrown
    }

    /** Outage with nothing cached propagates RegistryUnavailableException. */
    @Test
    void outageWithEmptyCache_throws() {
        when(client.fetchByCoordinates(any())).thenThrow(new RegistryUnavailableException("down"));
        SchemaResolver resolver = new SchemaResolver(client, new ApicurioCacheProperties());

        assertThatThrownBy(() -> resolver.resolveByCoordinates(COORDS))
                .isInstanceOf(RegistryUnavailableException.class);
    }

    /** Minimal controllable Caffeine ticker for deterministic TTL tests. */
    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
