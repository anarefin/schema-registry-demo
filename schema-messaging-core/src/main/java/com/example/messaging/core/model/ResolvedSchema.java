package com.example.messaging.core.model;

/**
 * A fully resolved schema entry held in the Caffeine cache.
 * {@code stale=true} when the registry was unreachable and this is last-known-good content.
 */
public record ResolvedSchema(
        long globalId,
        SchemaType schemaType,
        byte[] rawContent,
        boolean stale
) {

    /** Convenience constructor for freshly fetched (non-stale) schemas. */
    public ResolvedSchema(long globalId, SchemaType schemaType, byte[] rawContent) {
        this(globalId, schemaType, rawContent, false);
    }

    /** Returns a stale-marked copy of this schema. */
    public ResolvedSchema asStale() {
        return new ResolvedSchema(globalId, schemaType, rawContent, true);
    }
}
