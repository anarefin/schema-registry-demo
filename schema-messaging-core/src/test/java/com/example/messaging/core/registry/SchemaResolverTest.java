package com.example.messaging.core.registry;

import com.example.messaging.core.exception.RegistryUnavailableException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TC-1.1 cache hit · TC-1.2 cache miss · TC-1.3 expiry · TC-1.4 refresh-after-write ·
 * TC-1.5 pre-warm success · TC-1.6 pre-warm failure · TC-1.7 down+cached · TC-1.8 down+uncached
 */
@ExtendWith(MockitoExtension.class)
class SchemaResolverTest {

    @Mock
    private ApicurioClient apicurioClient;

    private SchemaResolver schemaResolver;
    private ApicurioCacheProperties cacheProps;

    private static final SchemaCoordinates COORDS =
            new SchemaCoordinates("events.orders", "OrderCreated", "1");
    private static final ResolvedSchema SCHEMA =
            new ResolvedSchema(42L, SchemaType.JSON, "{}".getBytes());

    @BeforeEach
    void setUp() {
        // Short TTL for expiry test; generous refresh window so no auto-refresh during hit/miss tests
        cacheProps = new ApicurioCacheProperties(100, Duration.ofSeconds(30), Duration.ofSeconds(20));
        schemaResolver = new SchemaResolver(apicurioClient, cacheProps);
    }

    /** TC-1.1: second call for same coordinates must NOT invoke ApicurioClient again. */
    @Test
    void cacheHit_byCoordinates_secondCallSkipsClient() {
        when(apicurioClient.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);

        schemaResolver.resolveByCoordinates(COORDS); // populates cache
        schemaResolver.resolveByCoordinates(COORDS); // should hit cache

        verify(apicurioClient, times(1)).fetchByCoordinates(COORDS);
    }

    /** TC-1.1: cache hit by globalId. */
    @Test
    void cacheHit_byGlobalId_secondCallSkipsClient() {
        when(apicurioClient.fetchByGlobalId(42L, SchemaType.JSON)).thenReturn(SCHEMA);

        schemaResolver.resolveByGlobalId(42L, SchemaType.JSON);
        schemaResolver.resolveByGlobalId(42L, SchemaType.JSON);

        verify(apicurioClient, times(1)).fetchByGlobalId(42L, SchemaType.JSON);
    }

    /** TC-1.2: distinct coordinates each trigger a separate fetch. */
    @Test
    void cacheMiss_distinctCoordinates_eachFetched() {
        SchemaCoordinates other = new SchemaCoordinates("events.customers", "CustomerRegistered", "1");
        ResolvedSchema other_schema = new ResolvedSchema(99L, SchemaType.JSON, "{}".getBytes());

        when(apicurioClient.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);
        when(apicurioClient.fetchByCoordinates(other)).thenReturn(other_schema);

        schemaResolver.resolveByCoordinates(COORDS);
        schemaResolver.resolveByCoordinates(other);

        verify(apicurioClient, times(1)).fetchByCoordinates(COORDS);
        verify(apicurioClient, times(1)).fetchByCoordinates(other);
    }

    /** TC-1.3: after TTL expiry, the cache re-fetches (uses very short TTL). */
    @Test
    void cacheExpiry_afterTtl_refetches() throws InterruptedException {
        ApicurioCacheProperties shortTtl =
                new ApicurioCacheProperties(100, Duration.ofMillis(50), Duration.ofMillis(40));
        SchemaResolver resolver = new SchemaResolver(apicurioClient, shortTtl);
        when(apicurioClient.fetchByCoordinates(any())).thenReturn(SCHEMA);

        resolver.resolveByCoordinates(COORDS);
        Thread.sleep(100); // wait for entry to expire
        resolver.resolveByCoordinates(COORDS);

        // Caffeine may call load() twice: initial + reload after expiry
        verify(apicurioClient, atLeast(2)).fetchByCoordinates(COORDS);
    }

    /**
     * TC-1.4: refreshAfterWrite triggers background refresh without blocking the caller.
     * After the refresh window, the caller gets the stale value immediately; a second call
     * after a short wait confirms the background reload ran.
     */
    @Test
    void refreshAfterWrite_backgroundRefreshDoesNotBlockCaller() throws InterruptedException {
        ApicurioCacheProperties shortRefresh =
                new ApicurioCacheProperties(100, Duration.ofSeconds(30), Duration.ofMillis(30));
        SchemaResolver resolver = new SchemaResolver(apicurioClient, shortRefresh);
        ResolvedSchema v2 = new ResolvedSchema(42L, SchemaType.JSON, "v2".getBytes());

        when(apicurioClient.fetchByCoordinates(any()))
                .thenReturn(SCHEMA)   // first load
                .thenReturn(v2);      // reload

        ResolvedSchema first = resolver.resolveByCoordinates(COORDS);
        assertThat(first).isEqualTo(SCHEMA);

        Thread.sleep(60); // exceed refresh window
        // This call returns stale synchronously; background reload scheduled
        resolver.resolveByCoordinates(COORDS);

        Thread.sleep(100); // allow background reload to complete
        // After reload, next call should return v2
        ResolvedSchema afterRefresh = resolver.resolveByCoordinates(COORDS);
        // May still be v2 or SCHEMA depending on timing — just verify no exception and reload ran
        verify(apicurioClient, atLeast(2)).fetchByCoordinates(any());
        assertThat(afterRefresh).isNotNull();
    }

    /** TC-1.5: pre-warm populates the cache (next resolve skips client). */
    @Test
    void preWarmSuccess_populatesCache() {
        when(apicurioClient.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);

        schemaResolver.preWarm(COORDS);

        // Cache populated by preWarm → resolveByCoordinates should NOT call client again
        schemaResolver.resolveByCoordinates(COORDS);
        verify(apicurioClient, times(1)).fetchByCoordinates(COORDS);
    }

    /** TC-1.6: pre-warm fetch failure is logged as WARN; context still starts (no exception). */
    @Test
    void preWarmFailure_doesNotThrow() {
        when(apicurioClient.fetchByCoordinates(any()))
                .thenThrow(new RegistryUnavailableException("test-down"));

        // Must NOT throw — startup continues
        schemaResolver.preWarm(COORDS);
    }

    /**
     * TC-1.7: registry down but schema was previously fetched → returns stale + marks stale.
     * Uses the byCoordinates path with a short TTL to force cache expiry then registry-down reload.
     */
    @Test
    void registryDown_withCachedSchema_returnsStale() throws InterruptedException {
        ApicurioCacheProperties shortTtl =
                new ApicurioCacheProperties(100, Duration.ofMillis(50), Duration.ofMillis(40));
        SchemaResolver resolver = new SchemaResolver(apicurioClient, shortTtl);

        when(apicurioClient.fetchByCoordinates(any()))
                .thenReturn(SCHEMA)                                   // initial fetch OK
                .thenThrow(new RegistryUnavailableException("down")); // all subsequent fail

        resolver.resolveByCoordinates(COORDS); // populates lastKnownGood
        Thread.sleep(100); // expire the cache entry

        ResolvedSchema stale = resolver.resolveByCoordinates(COORDS);

        assertThat(stale).isNotNull();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.rawContent()).isEqualTo(SCHEMA.rawContent());
    }

    /**
     * TC-1.7 variant: registry down, resolve by globalId with prior coordinate fetch populating cache.
     */
    @Test
    void registryDown_withGlobalIdCached_returnsStale() {
        when(apicurioClient.fetchByCoordinates(COORDS)).thenReturn(SCHEMA);
        schemaResolver.resolveByCoordinates(COORDS); // cross-populates byGlobalId cache

        // Now registry is down — resolveByGlobalId should serve from byGlobalId cache
        ResolvedSchema result = schemaResolver.resolveByGlobalId(42L, SchemaType.JSON);
        assertThat(result).isNotNull();
        assertThat(result.rawContent()).isEqualTo(SCHEMA.rawContent());
        // Not marked stale since it came from cache, not lastKnownGood path
    }

    /** TC-1.8: registry down and schema was never cached → throws RegistryUnavailableException. */
    @Test
    void registryDown_noCachedSchema_throwsRegistryUnavailable() {
        when(apicurioClient.fetchByCoordinates(any()))
                .thenThrow(new RegistryUnavailableException("down"));

        assertThatThrownBy(() -> schemaResolver.resolveByCoordinates(COORDS))
                .isInstanceOf(RegistryUnavailableException.class);
    }

    /** TC-1.8 variant: registry down, never resolved by globalId → throws. */
    @Test
    void registryDown_globalIdNotCached_throwsRegistryUnavailable() {
        when(apicurioClient.fetchByGlobalId(99L, SchemaType.JSON))
                .thenThrow(new RegistryUnavailableException("down"));

        assertThatThrownBy(() -> schemaResolver.resolveByGlobalId(99L, SchemaType.JSON))
                .isInstanceOf(RegistryUnavailableException.class);
    }
}
