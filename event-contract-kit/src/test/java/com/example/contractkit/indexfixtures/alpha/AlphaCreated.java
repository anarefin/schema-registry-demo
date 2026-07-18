package com.example.contractkit.indexfixtures.alpha;

import com.example.amqp.topology.mapping.EventMapping;

/** Fixture event: default artifactId (blank → simple name "AlphaCreated"). */
@EventMapping(
        groupId = "events.alpha",
        exchange = "events.alpha.exchange",
        routingKey = "alpha.created")
public record AlphaCreated(String id) {}
