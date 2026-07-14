package com.example.contracts.orders.topology;

import com.example.amqp.topology.mapping.TypeMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTypeMappingArtifactIdTest {

    @Test
    void artifactIdEqualsJavaTypeSimpleNameForEveryMapping() {
        OrderTypeMappingAutoConfiguration config = new OrderTypeMappingAutoConfiguration();
        List<TypeMapping> mappings = List.of(
                config.orderCreatedMapping(),
                config.orderShippedMapping(),
                config.orderCancelledMapping(),
                config.orderFulfilledMapping());

        assertThat(mappings).allSatisfy(mapping ->
                assertThat(mapping.coordinates().artifactId())
                        .isEqualTo(mapping.javaType().getSimpleName()));
    }
}
