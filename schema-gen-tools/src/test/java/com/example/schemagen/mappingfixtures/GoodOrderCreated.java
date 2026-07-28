package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid paired event class; artifactId defaults to the simple name. */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = "events.orders.exchange",
        routingKey = "orders.created")
public final class GoodOrderCreated {

    private final String id;

    public GoodOrderCreated(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
