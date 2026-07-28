package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid shape, blank required {@code groupId}. */
@GenerateSchema
@EventMapping(groupId = "", exchange = "events.orders.exchange", routingKey = "orders.created")
public final class BlankGroupId {

    private final String id;

    public BlankGroupId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
