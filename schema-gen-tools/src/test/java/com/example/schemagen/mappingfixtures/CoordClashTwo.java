package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Shares effective coordinates {@code (events.clash, Same)} with {@link CoordClashOne}. */
@GenerateSchema
@EventMapping(
        groupId = "events.clash",
        exchange = "events.clash.exchange",
        routingKey = "clash.two",
        artifactId = "Same")
public final class CoordClashTwo {

    private final String id;

    public CoordClashTwo(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
