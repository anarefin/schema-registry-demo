package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Valid shape, blank required {@code exchange}. */
@GenerateSchema
@EventMapping(groupId = "events.orders", exchange = "   ", routingKey = "orders.created")
public record BlankExchange(String id) {}
