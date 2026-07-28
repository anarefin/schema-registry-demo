package com.example.schemagen.fixtures;

/**
 * Fixture with a nested object property so the additionalProperties invariant is checked on both
 * the root schema and inlined nested object nodes.
 */
public final class EventWithNestedObject {

    private final String id;
    private final NestedPayload payload;

    public EventWithNestedObject(String id, NestedPayload payload) {
        this.id = id;
        this.payload = payload;
    }

    public String getId() {
        return id;
    }

    public NestedPayload getPayload() {
        return payload;
    }
}
