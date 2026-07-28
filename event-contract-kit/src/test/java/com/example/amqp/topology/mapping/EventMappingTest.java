package com.example.amqp.topology.mapping;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class EventMappingTest {

    @EventMapping(
            groupId = "events.orders",
            exchange = "events.orders.exchange",
            routingKey = "orders.created")
    static final class SampleWithDefaults {
        private SampleWithDefaults() {}
    }

    @EventMapping(
            groupId = "events.customers",
            exchange = "events.customers.exchange",
            routingKey = "customers.registered",
            artifactId = "CustomArtifact",
            schemaType = SchemaType.JSON)
    static final class SampleWithExplicitValues {
        private SampleWithExplicitValues() {}
    }

    @Test
    void defaultsArtifactIdToEmptyAndSchemaTypeToJson() {
        EventMapping mapping = SampleWithDefaults.class.getAnnotation(EventMapping.class);

        assertThat(mapping.groupId()).isEqualTo("events.orders");
        assertThat(mapping.exchange()).isEqualTo("events.orders.exchange");
        assertThat(mapping.routingKey()).isEqualTo("orders.created");
        assertThat(mapping.artifactId()).isEmpty();
        assertThat(mapping.schemaType()).isEqualTo(SchemaType.JSON);
    }

    @Test
    void honorsExplicitArtifactIdAndSchemaType() {
        EventMapping mapping = SampleWithExplicitValues.class.getAnnotation(EventMapping.class);

        assertThat(mapping.artifactId()).isEqualTo("CustomArtifact");
        assertThat(mapping.schemaType()).isEqualTo(SchemaType.JSON);
    }

    @Test
    void isRuntimeRetainedTypeTargetedAndDocumentedWithNoSpringDependency() {
        assertThat(EventMapping.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(EventMapping.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.TYPE);
        assertThat(EventMapping.class.isAnnotationPresent(Documented.class)).isTrue();

        // The annotation must carry no Spring (or other framework) meta-annotations.
        for (Annotation meta : EventMapping.class.getAnnotations()) {
            assertThat(meta.annotationType().getName())
                    .doesNotContain("springframework");
        }
    }
}
