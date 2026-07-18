package com.example.schemagen.mappingfixtures.beanclash;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Same simple name as {@code beanclash.sub.Widget} → identical bean name, distinct coordinates. */
@GenerateSchema
@EventMapping(
        groupId = "events.widgets",
        exchange = "events.widgets.exchange",
        routingKey = "widgets.one",
        artifactId = "WidgetOne")
public record Widget(String id) {}
