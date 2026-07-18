package com.example.schemagen.mappingfixtures.beanclash.sub;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;

/** Same simple name as {@code beanclash.Widget} → identical bean name, distinct coordinates. */
@GenerateSchema
@EventMapping(
        groupId = "events.widgets",
        exchange = "events.widgets.exchange",
        routingKey = "widgets.two",
        artifactId = "WidgetTwo")
public record Widget(String id) {}
