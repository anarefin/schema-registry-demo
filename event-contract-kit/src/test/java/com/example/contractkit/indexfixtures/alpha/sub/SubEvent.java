package com.example.contractkit.indexfixtures.alpha.sub;

import com.example.amqp.topology.mapping.EventMapping;

/** Fixture event in a sub-package of {@code ...alpha} — must be excluded by an exact-package filter. */
@EventMapping(
        groupId = "events.alpha",
        exchange = "events.alpha.exchange",
        routingKey = "alpha.sub")
public record SubEvent(String id) {}
