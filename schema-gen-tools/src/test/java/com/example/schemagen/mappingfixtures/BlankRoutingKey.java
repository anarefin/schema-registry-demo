package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid shape, blank required {@code routingKey}. */
@GenerateSchema
@EventMapping(groupId = "events.orders", exchange = "events.orders.exchange", routingKey = "")
public record BlankRoutingKey(String id) {}
