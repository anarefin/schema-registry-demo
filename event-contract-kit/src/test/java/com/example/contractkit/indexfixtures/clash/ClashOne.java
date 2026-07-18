package com.example.contractkit.indexfixtures.clash;

import com.example.amqp.topology.mapping.EventMapping;

/** Fixture event sharing effective coordinates {@code (events.clash, Dup)} with {@link ClashTwo}. */
@EventMapping(
        groupId = "events.clash",
        exchange = "events.clash.exchange",
        routingKey = "clash.one",
        artifactId = "Dup")
public record ClashOne(String id) {}
