package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.TypeMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerTypeMappingArtifactIdTest {

    @Test
    void artifactIdEqualsJavaTypeSimpleNameForEveryMapping() {
        CustomerTypeMappingAutoConfiguration config = new CustomerTypeMappingAutoConfiguration();
        List<TypeMapping> mappings = List.of(
                config.customerRegisteredMapping(),
                config.customerAddressAddedMapping(),
                config.customerTierChangedMapping());

        assertThat(mappings).allSatisfy(mapping ->
                assertThat(mapping.coordinates().artifactId())
                        .isEqualTo(mapping.javaType().getSimpleName()));
    }
}
