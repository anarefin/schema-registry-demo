package com.example.messaging.core.registry;

import com.example.messaging.core.exception.RegistryUnavailableException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caching layer over {@link ApicurioClient} (spec §10.2).
 *
 * <p>Two caches:
 * <ul>
 *   <li>{@code byCoordinates} — Caffeine {@link LoadingCache} with TTL + refreshAfterWrite.
 *   <li>{@code byGlobalId} — plain Caffeine {@link Cache} cross-populated on every coordinate fetch
 *       (the globalIds SDK endpoint returns content-only, so the type must come from context).
 * </ul>
 *
 * <p>Stale-on-failure strategy:
 * <ul>
 *   <li>During background {@code reload()}: registry down → returns stale + WARN (TC-1.4/TC-1.7).
 *   <li>After TTL expiry with registry down: checks {@code lastKnownGood} → stale + WARN (TC-1.7).
 *   <li>Not cached + registry down → throws {@link RegistryUnavailableException} (TC-1.8).
 * </ul>
 */
public class SchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(SchemaResolver.class);

    private final ApicurioClient apicurioClient;

    /**
     * Primary coordinate cache: supports auto-refresh-after-write (TC-1.3, TC-1.4).
     */
    private final LoadingCache<SchemaCoordinates, ResolvedSchema> byCoordinates;

    /**
     * Secondary global-ID cache: plain cache cross-populated by coordinate fetches.
     * The globalIds SDK endpoint returns raw content only, so SchemaType is injected by the caller.
     */
    private final Cache<Long, ResolvedSchema> byGlobalId;

    // Survive cache TTL expiry when registry is down (TC-1.7)
    private final ConcurrentHashMap<Long, ResolvedSchema> lastKnownGoodById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SchemaCoordinates, ResolvedSchema> lastKnownGoodByCoords = new ConcurrentHashMap<>();

    public SchemaResolver(ApicurioClient apicurioClient, ApicurioCacheProperties props) {
        this.apicurioClient = apicurioClient;
        this.byCoordinates = buildCoordinatesCache(props);
        this.byGlobalId = Caffeine.newBuilder()
                .maximumSize(props.maxSize())
                .expireAfterWrite(props.ttl())
                .build();
    }

    // ---- public API -------------------------------------------------------

    /**
     * Resolve by global ID using the schema type from message headers.
     * The type is required because the Apicurio SDK's globalIds endpoint returns content only.
     * Checks {@code byGlobalId} cache first (populated by prior coordinate fetches).
     */
    public ResolvedSchema resolveByGlobalId(long globalId, SchemaType schemaType) {
        ResolvedSchema cached = byGlobalId.getIfPresent(globalId);
        if (cached != null) {
            return cached;
        }
        return loadById(globalId, schemaType);
    }

    public ResolvedSchema resolveByCoordinates(SchemaCoordinates coords) {
        // byCoordinates is a LoadingCache: get() returns the cached value if present,
        // otherwise loads via the CacheLoader. No separate getIfPresent() check needed.
        return byCoordinates.get(coords);
    }

    /**
     * Pre-warm a schema into both caches (T-1.4). On fetch failure: WARN + continue.
     */
    public void preWarm(SchemaCoordinates coords) {
        try {
            ResolvedSchema schema = apicurioClient.fetchByCoordinates(coords);
            storeInAllCaches(schema, coords);
            byCoordinates.put(coords, schema);
            log.debug("Pre-warmed schema: {}", coords);
        } catch (Exception e) {
            log.warn("Pre-warm failed for {} — continuing startup", coords, e);
        }
    }

    // ---- byCoordinates LoadingCache builder --------------------------------

    private LoadingCache<SchemaCoordinates, ResolvedSchema> buildCoordinatesCache(ApicurioCacheProperties props) {
        return Caffeine.newBuilder()
                .maximumSize(props.maxSize())
                .expireAfterWrite(props.ttl())
                .refreshAfterWrite(props.refreshAfterWrite())
                .build(new CacheLoader<>() {
                    @Override
                    public ResolvedSchema load(SchemaCoordinates coords) {
                        return loadByCoords(coords);
                    }

                    @Override
                    public ResolvedSchema reload(SchemaCoordinates coords, ResolvedSchema oldValue) {
                        try {
                            ResolvedSchema fresh = apicurioClient.fetchByCoordinates(coords);
                            storeInAllCaches(fresh, coords);
                            return fresh;
                        } catch (Exception e) {
                            log.warn("Registry unavailable during refresh for {}, serving stale", coords, e);
                            return oldValue.asStale();
                        }
                    }
                });
    }

    // ---- load helpers -----------------------------------------------------

    private ResolvedSchema loadByCoords(SchemaCoordinates coords) {
        try {
            ResolvedSchema schema = apicurioClient.fetchByCoordinates(coords);
            storeInAllCaches(schema, coords);
            return schema;
        } catch (RegistryUnavailableException e) {
            ResolvedSchema stale = lastKnownGoodByCoords.get(coords);
            if (stale != null) {
                log.warn("Registry unavailable for {}, serving last-known-good (stale)", coords, e);
                return stale.asStale();
            }
            throw e;
        }
    }

    private ResolvedSchema loadById(long globalId, SchemaType schemaType) {
        String ctx = "globalId=" + globalId;
        try {
            ResolvedSchema schema = apicurioClient.fetchByGlobalId(globalId, schemaType);
            storeInAllCaches(schema, null);
            return schema;
        } catch (RegistryUnavailableException e) {
            ResolvedSchema stale = lastKnownGoodById.get(globalId);
            if (stale != null) {
                log.warn("Registry unavailable for {}, serving last-known-good (stale)", ctx, e);
                return stale.asStale();
            }
            throw e;
        }
    }

    private void storeInAllCaches(ResolvedSchema schema, SchemaCoordinates coords) {
        byGlobalId.put(schema.globalId(), schema);
        lastKnownGoodById.put(schema.globalId(), schema);
        if (coords != null) {
            lastKnownGoodByCoords.put(coords, schema);
        }
    }
}
