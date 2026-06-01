package com.example.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-6.5 (I): With {@code apicurio.auto-register=OFF} and the registry unreachable,
 * the application context must FAIL to start with a clear error (T-6.6 / AC-6.3).
 *
 * <p>Points {@code apicurio.registry.url} at an unreachable host so every schema fetch
 * fails → {@link com.example.messaging.core.registry.StartupSchemaValidator} throws
 * {@link IllegalStateException}, aborting context startup.
 */
@Testcontainers
class StartupSchemaValidatorIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * TC-6.5: Context must refuse to start when auto-register=OFF and registry is unreachable.
     */
    @Test
    void tc65_autoRegisterOff_registryUnreachable_contextFailsToStart() {
        SpringApplication app = new SpringApplication(ConsumerApplication.class);
        // Command-line args have highest priority — they override application.yml defaults
        // (setDefaultProperties is lowest priority and would be overridden by application.yml)
        String[] args = {
            "--apicurio.auto-register=OFF",
            "--apicurio.registry.url=http://localhost:9999",
            "--spring.rabbitmq.host=" + rabbitMQ.getHost(),
            "--spring.rabbitmq.port=" + rabbitMQ.getAmqpPort(),
            "--spring.main.web-application-type=none"
        };

        assertThatThrownBy(() -> app.run(args))
            .isInstanceOf(Exception.class)
            .satisfies(ex -> {
                // Walk the cause chain to find the root cause message
                Throwable t = ex;
                boolean found = false;
                while (t != null) {
                    if (t.getMessage() != null && (
                            t.getMessage().contains("Startup aborted") ||
                            t.getMessage().contains("schema") ||
                            t.getMessage().contains("Connection refused"))) {
                        found = true;
                        break;
                    }
                    t = t.getCause();
                }
                org.assertj.core.api.Assertions.assertThat(found)
                    .as("Expected startup-abort message in cause chain; got: " + ex.getMessage())
                    .isTrue();
            });
    }
}
