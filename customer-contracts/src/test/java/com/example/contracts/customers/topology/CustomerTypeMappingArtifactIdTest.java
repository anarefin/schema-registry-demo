package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.TypeMapping;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerTypeMappingArtifactIdTest {

    @Test
    void artifactIdEqualsJavaTypeSimpleNameForEveryMapping() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeneratedEventTypeMappings.class)
                .run(context -> assertThat(context.getBeansOfType(TypeMapping.class).values())
                        .hasSize(3)
                        .allSatisfy(mapping ->
                                assertThat(mapping.getCoordinates().getArtifactId())
                                        .isEqualTo(mapping.getJavaType().getSimpleName())));
    }
}
