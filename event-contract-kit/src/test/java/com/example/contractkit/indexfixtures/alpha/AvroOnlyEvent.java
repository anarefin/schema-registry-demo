package com.example.contractkit.indexfixtures.alpha;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.SchemaType;

/** Fixture: non-JSON schemaType must fail at IndexedEventMappings.load. */
@EventMapping(
        groupId = "events.alpha",
        exchange = "events.alpha.exchange",
        routingKey = "alpha.avro-only",
        schemaType = SchemaType.AVRO)
public record AvroOnlyEvent(String id) {}
