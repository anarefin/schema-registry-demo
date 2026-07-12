package com.example.consumer.health;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.BitsEventHandlerScanner;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reports RabbitMQ queue and DLQ message counts (AC-6.1). Derives each handled event's queue
 * ({@code rk.{serviceName}.queue}) and DLQ ({@code rk.{serviceName}.dlq}) from the same
 * {@code @BitsEventHandler} scan {@code ServiceQueueTopologyAutoConfiguration} uses to declare
 * them — not every {@link TypeMapping} in the registry, since only handled event types get a
 * per-service queue declared for this service. Iterating the full registry would probe queues
 * this service never declared once a service only handles a subset of event types.
 *
 * <p>Status is DOWN when any DLQ contains messages (signals processing failures). Main-queue depth
 * is reported as info; it never triggers DOWN on its own.
 */
@Component
public class QueueDepthHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthHealthIndicator.class);

    private final RabbitAdmin rabbitAdmin;
    private final ApplicationContext applicationContext;
    private final TypeMappingRegistry typeMappingRegistry;
    private final String serviceName;

    public QueueDepthHealthIndicator(
            RabbitAdmin rabbitAdmin,
            ApplicationContext applicationContext,
            TypeMappingRegistry typeMappingRegistry,
            @Value("${spring.application.name}") String serviceName) {
        this.rabbitAdmin = rabbitAdmin;
        this.applicationContext = applicationContext;
        this.typeMappingRegistry = typeMappingRegistry;
        this.serviceName = serviceName;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean dlqEmpty = true;

            Set<TypeMapping> handledMappings =
                    BitsEventHandlerScanner.discoverHandledTypeMappings(applicationContext, typeMappingRegistry);
            for (TypeMapping mapping : handledMappings) {
                String rk = mapping.routingKey();
                int dlqDepth = queueDepth(TopologyNaming.serviceDlqName(rk, serviceName), details);
                queueDepth(TopologyNaming.serviceQueueName(rk, serviceName), details);
                if (dlqDepth > 0) {
                    dlqEmpty = false;
                }
            }

            return (dlqEmpty ? Health.up() : Health.down())
                    .withDetails(details).build();
        } catch (Exception e) {
            log.warn("Queue depth health check failed", e);
            return Health.unknown().withException(e).build();
        }
    }

    private int queueDepth(String queueName, Map<String, Object> details) {
        try {
            var info = rabbitAdmin.getQueueInfo(queueName);
            int depth = info != null ? (int) info.getMessageCount() : -1;
            details.put(queueName + ".depth", depth);
            return depth;
        } catch (Exception e) {
            details.put(queueName + ".depth", "error: " + e.getMessage());
            return 0;
        }
    }
}
