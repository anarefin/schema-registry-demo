package com.example.contractkit.indexfixtures.dupname;

import com.example.amqp.topology.mapping.EventMapping;

/** Shares the simple name (and thus bean name) with {@code ...dupname.other.StatusChanged}. */
@EventMapping(
        groupId = "events.dupname",
        exchange = "events.dupname.exchange",
        routingKey = "dupname.status")
public record StatusChanged(String id) {}
