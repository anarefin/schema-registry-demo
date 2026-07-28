package com.example.schemagen.mappingfixtures;

/** Host for a nested (member) class — unsupported shape when annotated. */
public class Outer {

    /** Nested member class; must be rejected in favour of top-level event classes. */
    public static final class Nested {

        private final String id;

        public Nested(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
