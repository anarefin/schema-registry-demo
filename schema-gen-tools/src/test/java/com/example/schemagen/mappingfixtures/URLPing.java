package com.example.schemagen.mappingfixtures;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Leading-acronym class: bean name stays {@code URLPingMapping} (decapitalize leaves it). */
@GenerateSchema
@EventMapping(
        groupId = "events.net",
        exchange = "events.net.exchange",
        routingKey = "net.ping")
public final class URLPing {

    private final String id;

    public URLPing(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
