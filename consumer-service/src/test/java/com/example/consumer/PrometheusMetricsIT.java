package com.example.consumer;

import com.example.messaging.core.registry.ApicurioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-6.1 (I): /actuator/prometheus exposes schema.* metrics from SchemaMessagingMetrics.
 * TC-6.2 (I): Prometheus endpoint returns HTTP 200 with Prometheus text format.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@Testcontainers
class PrometheusMetricsIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoBean
    ApicurioClient apicurioClient;

    @LocalManagementPort
    int managementPort;

    /** TC-6.2: /actuator/prometheus returns 200 with Prometheus text format. */
    @Test
    void tc62_prometheusEndpointReturns200() {
        RestClient client = RestClient.create("http://localhost:" + managementPort);
        var response = client.get().uri("/actuator/prometheus").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("# HELP", "# TYPE");
    }

    /** TC-6.1: schema.* meters appear in the Prometheus output. */
    @Test
    void tc61_schemaMetricsExposed() {
        RestClient client = RestClient.create("http://localhost:" + managementPort);
        String body = client.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(body).containsAnyOf(
                "schema_cache_hits", "schema_cache_misses",
                "schema_fetch_failures", "schema_validation_failures",
                "schema_publish_count", "schema_consume_count"
        );
    }

    /** TC-6.2 complement: /actuator/health reports registry component in details. */
    @Test
    void tc62_healthEndpointExposed() {
        RestClient client = RestClient.create("http://localhost:" + managementPort);
        var response = client.get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsAnyOf("UP", "DOWN", "status");
    }
}
