package com.example.consumer;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.exception.SchemaNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0004: Spring context must abort when a registered {@link TypeMapping} has no classpath
 * schema resource (replaces the deleted StartupSchemaValidatorIT for the local-catalog model).
 */
@Testcontainers
class LocalSchemaCatalogStartupIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * Probe type with no {@code schemas/missing-schema-probe.schema.json} on the classpath.
     * Filename comes from {@code SchemaFileNaming.toFileName(simpleName)}.
     */
    record MissingSchemaProbe(String id) {}

    @Configuration
    static class BrokenMappingConfig {
        @Bean
        TypeMapping missingSchemaProbeMapping() {
            return new TypeMapping(
                    MissingSchemaProbe.class,
                    new SchemaCoordinates("events.test", "MissingSchemaProbe"),
                    SchemaType.JSON,
                    "test.missing",
                    "events.test.exchange");
        }
    }

    @Test
    void contextRefreshFailsWhenClasspathSchemaMissing() {
        assertThatThrownBy(() ->
                new SpringApplicationBuilder(ConsumerApplication.class)
                        .web(WebApplicationType.NONE)
                        .sources(BrokenMappingConfig.class)
                        .run(
                                "--spring.main.banner-mode=off",
                                "--spring.rabbitmq.host=" + rabbitMQ.getHost(),
                                "--spring.rabbitmq.port=" + rabbitMQ.getAmqpPort(),
                                "--spring.rabbitmq.username=" + rabbitMQ.getAdminUsername(),
                                "--spring.rabbitmq.password=" + rabbitMQ.getAdminPassword()
                        ))
                .hasRootCauseInstanceOf(SchemaNotFoundException.class)
                .rootCause()
                .hasMessageContaining("MissingSchemaProbe");
    }
}
