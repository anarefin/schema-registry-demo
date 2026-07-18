package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Leading-acronym record: bean name stays {@code URLPingMapping} (decapitalize leaves it). */
@GenerateSchema
@EventMapping(
        groupId = "events.net",
        exchange = "events.net.exchange",
        routingKey = "net.ping")
public record URLPing(String id) {}
