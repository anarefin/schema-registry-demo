package com.example.contractkit.indexfixtures.dupname.other;

import com.example.amqp.topology.mapping.EventMapping;

/** Distinct coordinates from {@code ...dupname.StatusChanged}, but the same bean name. */
@EventMapping(
        groupId = "events.dupname.other",
        exchange = "events.dupname.other.exchange",
        routingKey = "dupname.other.status")
public record StatusChanged(String id) {}
