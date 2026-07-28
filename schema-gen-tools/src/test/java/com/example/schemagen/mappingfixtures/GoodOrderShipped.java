package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid paired event class with an explicit artifactId that differs from the simple name. */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = "events.orders.exchange",
        routingKey = "orders.shipped",
        artifactId = "OrderShipped")
public final class GoodOrderShipped {

    private final String id;

    public GoodOrderShipped(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
