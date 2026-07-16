package com.example.schemagen.fixtures;

/**
 * Fixture with a nested object property so the additionalProperties invariant is checked on both
 * the root schema and inlined nested object nodes.
 */
public record EventWithNestedObject(String id, NestedPayload payload) {}
