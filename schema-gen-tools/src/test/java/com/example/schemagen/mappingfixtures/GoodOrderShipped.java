package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid paired event record with an explicit artifactId that differs from the simple name. */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = "events.orders.exchange",
        routingKey = "orders.shipped",
        artifactId = "OrderShipped")
public record GoodOrderShipped(String id) {}
