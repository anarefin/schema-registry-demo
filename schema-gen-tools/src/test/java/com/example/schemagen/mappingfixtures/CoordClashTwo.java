package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Shares effective coordinates {@code (events.clash, Same)} with {@link CoordClashOne}. */
@GenerateSchema
@EventMapping(
        groupId = "events.clash",
        exchange = "events.clash.exchange",
        routingKey = "clash.two",
        artifactId = "Same")
public record CoordClashTwo(String id) {}
