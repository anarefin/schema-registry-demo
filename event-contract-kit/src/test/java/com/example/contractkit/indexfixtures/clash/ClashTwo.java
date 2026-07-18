package com.example.contractkit.indexfixtures.clash;

import com.example.amqp.topology.mapping.EventMapping;

/** Fixture event sharing effective coordinates {@code (events.clash, Dup)} with {@link ClashOne}. */
@EventMapping(
        groupId = "events.clash",
        exchange = "events.clash.exchange",
        routingKey = "clash.two",
        artifactId = "Dup")
public record ClashTwo(String id) {}
