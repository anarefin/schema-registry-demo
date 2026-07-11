package com.example.messaging.core.mapping;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeMappingRegistryTest {

    record A(String id) {}
    record B(String id) {}

    @Test
    void duplicateJavaType_throwsWithClearMessage() {
        TypeMapping first = new TypeMapping(A.class,
                new SchemaCoordinates("g", "A"), SchemaType.JSON, "a");
        TypeMapping second = new TypeMapping(A.class,
                new SchemaCoordinates("g", "A2"), SchemaType.JSON, "a2");

        assertThatThrownBy(() -> new TypeMappingRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java type")
                .hasMessageContaining(A.class.getName());
    }

    @Test
    void duplicateCoordinates_throwsWithClearMessage() {
        SchemaCoordinates coords = new SchemaCoordinates("g", "Same");
        TypeMapping first = new TypeMapping(A.class, coords, SchemaType.JSON, "a");
        TypeMapping second = new TypeMapping(B.class, coords, SchemaType.JSON, "b");

        assertThatThrownBy(() -> new TypeMappingRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coordinates")
                .hasMessageContaining("g:Same");
    }

    @Test
    void findByGroupAndArtifact_returnsMapping() {
        TypeMapping mapping = new TypeMapping(A.class,
                new SchemaCoordinates("events.orders", "OrderCreated"),
                SchemaType.JSON, "orders.created");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        assertThat(registry.findByGroupAndArtifact("events.orders", "OrderCreated"))
                .contains(mapping);
        assertThat(registry.findByJavaType(A.class)).contains(mapping);
    }
}
