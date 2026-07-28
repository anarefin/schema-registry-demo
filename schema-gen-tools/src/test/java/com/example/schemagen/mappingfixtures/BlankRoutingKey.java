package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid shape, blank required {@code routingKey}. */
@GenerateSchema
@EventMapping(groupId = "events.orders", exchange = "events.orders.exchange", routingKey = "")
public final class BlankRoutingKey {

    private final String id;

    public BlankRoutingKey(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
