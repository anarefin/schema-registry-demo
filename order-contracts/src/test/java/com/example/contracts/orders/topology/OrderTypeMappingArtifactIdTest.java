package com.example.contracts.orders.topology;

import com.example.amqp.topology.mapping.TypeMapping;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTypeMappingArtifactIdTest {

    @Test
    void artifactIdEqualsJavaTypeSimpleNameForEveryMapping() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeneratedEventTypeMappings.class)
                .run(context -> assertThat(context.getBeansOfType(TypeMapping.class).values())
                        .hasSize(4)
                        .allSatisfy(mapping ->
                                assertThat(mapping.coordinates().artifactId())
                                        .isEqualTo(mapping.javaType().getSimpleName())));
    }
}
