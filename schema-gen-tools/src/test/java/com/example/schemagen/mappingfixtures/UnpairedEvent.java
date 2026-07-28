package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid class shape marked for schema generation but missing its paired {@code @EventMapping}. */
@GenerateSchema
public final class UnpairedEvent {

    private final String id;

    public UnpairedEvent(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
