package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid paired event record; artifactId defaults to the simple name. */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = "events.orders.exchange",
        routingKey = "orders.created")
public record GoodOrderCreated(String id) {}
