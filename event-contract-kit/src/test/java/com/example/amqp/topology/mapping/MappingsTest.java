package com.example.amqp.topology.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MappingsTest {

    static class SampleEvent {}

    @Test
    void jsonDefaultsArtifactIdToSimpleName() {
        TypeMapping mapping = Mappings.forDomain("events.orders", "events.orders.exchange")
                .json(SampleEvent.class, "orders.created");

        assertThat(mapping.javaType()).isEqualTo(SampleEvent.class);
        assertThat(mapping.coordinates().groupId()).isEqualTo("events.orders");
        assertThat(mapping.coordinates().artifactId()).isEqualTo("SampleEvent");
        assertThat(mapping.schemaType()).isEqualTo(SchemaType.JSON);
        assertThat(mapping.routingKey()).isEqualTo("orders.created");
        assertThat(mapping.exchange()).isEqualTo("events.orders.exchange");
    }

    @Test
    void jsonHonorsExplicitArtifactId() {
        TypeMapping mapping = Mappings.forDomain("events.customers", "events.customers.exchange")
                .json(SampleEvent.class, "CustomArtifact", "customers.registered");

        assertThat(mapping.coordinates().artifactId()).isEqualTo("CustomArtifact");
        assertThat(mapping.routingKey()).isEqualTo("customers.registered");
        assertThat(mapping.exchange()).isEqualTo("events.customers.exchange");
    }
}
