package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid shape, blank required {@code groupId}. */
@GenerateSchema
@EventMapping(groupId = "", exchange = "events.orders.exchange", routingKey = "orders.created")
public record BlankGroupId(String id) {}
