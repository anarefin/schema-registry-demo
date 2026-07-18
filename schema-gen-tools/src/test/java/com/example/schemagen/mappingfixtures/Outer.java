package com.example.schemagen.mappingfixtures;

/** Host for a nested (member) record — unsupported shape when annotated. */
public class Outer {

    /** Nested member record; must be rejected in favour of top-level event records. */
    public record Nested(String id) {}
}
