package com.example.messaging.core.mapping;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TypeMappingSelectionTest {

    static final class OrderCreated {
        private final String id;

        OrderCreated(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }
    }

    static final class OrderShipped {
        private final String id;

        OrderShipped(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }
    }

    static final class CustomerRegistered {
        private final String id;

        CustomerRegistered(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }
    }

    private static final TypeMapping ORDER_CREATED = mapping(OrderCreated.class, "events.orders", "OrderCreated", "orders.created");
    private static final TypeMapping ORDER_SHIPPED = mapping(OrderShipped.class, "events.orders", "OrderShipped", "orders.shipped");
    private static final TypeMapping CUSTOMER = mapping(CustomerRegistered.class, "events.customers", "CustomerRegistered", "customers.registered");

    private static final List<TypeMapping> ALL = List.of(ORDER_CREATED, ORDER_SHIPPED, CUSTOMER);

    @Test
    void producerNoHandlers_keepsAll() {
        List<TypeMapping> selected = TypeMappingSelection.select(
                ALL, Set.of(), TypeMappingSelection.emptyProperties());
        assertThat(selected).containsExactly(ORDER_CREATED, ORDER_SHIPPED, CUSTOMER);
    }

    @Test
    void consumerWithHandler_intersectsToHandledTypesOnly() {
        List<TypeMapping> selected = TypeMappingSelection.select(
                ALL, Set.of(OrderCreated.class), TypeMappingSelection.emptyProperties());
        assertThat(selected).containsExactly(ORDER_CREATED);
    }

    @Test
    void includeBySimpleName_overridesHandlerScoping() {
        TypeMappingSelectionProperties props = new TypeMappingSelectionProperties();
        props.setInclude(List.of("OrderShipped", "CustomerRegistered"));

        List<TypeMapping> selected = TypeMappingSelection.select(
                ALL, Set.of(OrderCreated.class), props);
        assertThat(selected).containsExactly(ORDER_SHIPPED, CUSTOMER);
    }

    @Test
    void includeByCoordinates() {
        TypeMappingSelectionProperties props = new TypeMappingSelectionProperties();
        props.setInclude(List.of("events.orders:OrderCreated"));

        List<TypeMapping> selected = TypeMappingSelection.select(ALL, Set.of(), props);
        assertThat(selected).containsExactly(ORDER_CREATED);
    }

    @Test
    void excludeRemovesAfterSelection() {
        TypeMappingSelectionProperties props = new TypeMappingSelectionProperties();
        props.setExclude(List.of("OrderShipped"));

        List<TypeMapping> selected = TypeMappingSelection.select(ALL, Set.of(), props);
        assertThat(selected).containsExactly(ORDER_CREATED, CUSTOMER);
    }

    @Test
    void excludeAfterHandlerIntersection() {
        TypeMappingSelectionProperties props = new TypeMappingSelectionProperties();
        props.setExclude(List.of(OrderCreated.class.getName()));

        List<TypeMapping> selected = TypeMappingSelection.select(
                ALL, Set.of(OrderCreated.class, OrderShipped.class), props);
        assertThat(selected).containsExactly(ORDER_SHIPPED);
    }

    private static TypeMapping mapping(Class<?> type, String group, String artifact, String routingKey) {
        return new TypeMapping(type, new SchemaCoordinates(group, artifact), SchemaType.JSON,
                routingKey, group + ".exchange");
    }
}
