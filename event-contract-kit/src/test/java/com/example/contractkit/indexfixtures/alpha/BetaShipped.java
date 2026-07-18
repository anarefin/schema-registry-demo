package com.example.contractkit.indexfixtures.alpha;

import com.example.amqp.topology.mapping.EventMapping;

/** Fixture event in the same package as {@link AlphaCreated}, with an explicit artifactId. */
@EventMapping(
        groupId = "events.alpha",
        exchange = "events.alpha.exchange",
        routingKey = "alpha.shipped",
        artifactId = "BetaShippedV1")
public record BetaShipped(String id) {}
