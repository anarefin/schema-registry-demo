package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid shape, blank required {@code exchange}. */
@GenerateSchema
@EventMapping(groupId = "events.orders", exchange = "   ", routingKey = "orders.created")
public final class BlankExchange {

    private final String id;

    public BlankExchange(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
