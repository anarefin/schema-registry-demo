package com.example.contractkit.indexfixtures.beta;

import com.example.amqp.topology.mapping.EventMapping;

/** Fixture event in a second domain package — stands in for a second contracts jar. */
@EventMapping(
        groupId = "events.beta",
        exchange = "events.beta.exchange",
        routingKey = "beta.registered")
public record GammaRegistered(String id) {}
