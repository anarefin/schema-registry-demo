package com.example.amqp.topology.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MappingsTest {

    static class SampleEvent {}

    @Test
    void jsonDefaultsArtifactIdToSimpleName() {
        TypeMapping mapping = Mappings.forDomain("events.orders", "events.orders.exchange")
                .json(SampleEvent.class, "orders.created");

        assertThat(mapping.getJavaType()).isEqualTo(SampleEvent.class);
        assertThat(mapping.getCoordinates().getGroupId()).isEqualTo("events.orders");
        assertThat(mapping.getCoordinates().getArtifactId()).isEqualTo("SampleEvent");
        assertThat(mapping.getSchemaType()).isEqualTo(SchemaType.JSON);
        assertThat(mapping.getRoutingKey()).isEqualTo("orders.created");
        assertThat(mapping.getExchange()).isEqualTo("events.orders.exchange");
    }

    @Test
    void jsonHonorsExplicitArtifactId() {
        TypeMapping mapping = Mappings.forDomain("events.customers", "events.customers.exchange")
                .json(SampleEvent.class, "CustomArtifact", "customers.registered");

        assertThat(mapping.getCoordinates().getArtifactId()).isEqualTo("CustomArtifact");
        assertThat(mapping.getRoutingKey()).isEqualTo("customers.registered");
        assertThat(mapping.getExchange()).isEqualTo("events.customers.exchange");
    }
}
