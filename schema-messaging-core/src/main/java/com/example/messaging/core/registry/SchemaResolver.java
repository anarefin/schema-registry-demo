package com.example.messaging.core.registry;

import com.example.messaging.core.exception.RegistryUnavailableException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caffeine-backed cache over {@link ApicurioClient}, using two caches with deliberately
 * different semantics:
 *
 * <ul>
 *   <li><b>{@code byCoordinates}</b> — keyed by group/artifact/version. A coordinate pinned to
 *       {@code latest} is <b>mutable</b> (a newly registered version changes what it resolves
 *       to), so this is a {@link LoadingCache} with {@code expireAfterWrite} (TTL) +
 *       {@code refreshAfterWrite} (async stale-while-revalidate). Using a {@code LoadingCache}
 *       means concurrent misses for the same key load <b>once</b> — no cache stampede.</li>
 *   <li><b>{@code byGlobalId}</b> — keyed by Apicurio global ID. A globalId always maps to the
 *       <b>same immutable content</b>, so this cache is size-bounded only, with <b>no TTL</b>
 *       (expiring it would just cause needless refetches of identical bytes). It is
 *       cross-populated on every coordinate fetch.</li>
 * </ul>
 *
 * <p>Both caches are size-bounded (no unbounded growth) and {@code recordStats()}-enabled for
 * observability. On a registry outage the resolver serves <b>last-known-good</b> content from
 * {@link #lastKnownGoodById}/{@link #lastKnownGoodByCoords}; it only throws
 * {@link RegistryUnavailableException} when nothing has ever been cached for the key.
 */
public class SchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(SchemaResolver.class);

    private final ApicurioClient apicurioClient;

    /** Mutable coordinate cache: TTL + async refresh so {@code latest} advances. */
    private final LoadingCache<SchemaCoordinates, ResolvedSchema> byCoordinates;

    /** Immutable global-ID cache: size-bounded, no TTL. */
    private final Cache<Long, ResolvedSchema> byGlobalId;

    // Survive TTL expiry during a full registry outage (serve-stale fallback).
    private final ConcurrentHashMap<Long, ResolvedSchema> lastKnownGoodById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SchemaCoordinates, ResolvedSchema> lastKnownGoodByCoords = new ConcurrentHashMap<>();

    public SchemaResolver(ApicurioClient apicurioClient, ApicurioCacheProperties props) {
        this(apicurioClient, props, Ticker.systemTicker());
    }

    /** Test seam: inject a controllable {@link Ticker} to drive TTL/refresh behaviour deterministically. */
    SchemaResolver(ApicurioClient apicurioClient, ApicurioCacheProperties props, Ticker ticker) {
        this.apicurioClient = apicurioClient;
        this.byCoordinates = Caffeine.newBuilder()
                .maximumSize(props.getMaxSize())
                .expireAfterWrite(props.getTtl())
                .refreshAfterWrite(props.getRefreshAfterWrite())
                .ticker(ticker)
                .recordStats()
                .build(coordinatesLoader());
        this.byGlobalId = Caffeine.newBuilder()
                .maximumSize(props.getMaxSize())
                .ticker(ticker)
                .recordStats()
                .build();
    }

    // ---- public API -------------------------------------------------------

    /**
     * Resolve by global ID (the fast consumer path). The {@link SchemaType} is required because
     * the Apicurio globalIds endpoint returns raw content only. Checks {@code byGlobalId} first
     * (populated by prior coordinate fetches).
     */
    public ResolvedSchema resolveByGlobalId(long globalId, SchemaType schemaType) {
        ResolvedSchema cached = byGlobalId.getIfPresent(globalId);
        if (cached != null) {
            return cached;
        }
        return loadById(globalId, schemaType);
    }

    public ResolvedSchema resolveByCoordinates(SchemaCoordinates coords) {
        return byCoordinates.get(coords);
    }

    // ---- byCoordinates loader ---------------------------------------------

    private CacheLoader<SchemaCoordinates, ResolvedSchema> coordinatesLoader() {
        return new CacheLoader<>() {
            @Override
            public ResolvedSchema load(SchemaCoordinates coords) {
                try {
                    ResolvedSchema schema = apicurioClient.fetchByCoordinates(coords);
                    storeFresh(schema, coords);
                    return schema;
                } catch (RegistryUnavailableException e) {
                    ResolvedSchema stale = lastKnownGoodByCoords.get(coords);
                    if (stale != null) {
                        log.warn("Registry unavailable for {}, serving last-known-good (stale)", coords, e);
                        return stale;
                    }
                    throw e;
                }
            }

            @Override
            public ResolvedSchema reload(SchemaCoordinates coords, ResolvedSchema oldValue) {
                try {
                    ResolvedSchema fresh = apicurioClient.fetchByCoordinates(coords);
                    storeFresh(fresh, coords);
                    return fresh;
                } catch (Exception e) {
                    log.warn("Registry unavailable during refresh for {}, keeping current value", coords, e);
                    return oldValue;
                }
            }
        };
    }

    // ---- byGlobalId loader -------------------------------------------------

    private ResolvedSchema loadById(long globalId, SchemaType schemaType) {
        try {
            ResolvedSchema schema = apicurioClient.fetchByGlobalId(globalId, schemaType);
            storeFresh(schema, null);
            return schema;
        } catch (RegistryUnavailableException e) {
            ResolvedSchema stale = lastKnownGoodById.get(globalId);
            if (stale != null) {
                log.warn("Registry unavailable for globalId={}, serving last-known-good (stale)", globalId, e);
                return stale;
            }
            throw e;
        }
    }

    /** Populate both caches + the last-known-good fallback maps from a freshly fetched schema. */
    private void storeFresh(ResolvedSchema schema, SchemaCoordinates coords) {
        byGlobalId.put(schema.globalId(), schema);
        lastKnownGoodById.put(schema.globalId(), schema);
        if (coords != null) {
            lastKnownGoodByCoords.put(coords, schema);
        }
    }
}
