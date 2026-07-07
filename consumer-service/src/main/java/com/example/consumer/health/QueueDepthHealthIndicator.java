package com.example.consumer.health;

import com.example.messaging.core.amqp.EventTopologyAutoConfiguration;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports RabbitMQ queue and DLQ message counts (AC-6.1). Iterates the {@link TypeMappingRegistry}
 * and derives each event's queue ({@code rk.queue}) and DLQ ({@code rk.dlq}) from its routing key,
 * so adding an event needs no edit here.
 *
 * <p>Status is DOWN when any DLQ contains messages (signals processing failures). Main-queue depth
 * is reported as info; it never triggers DOWN on its own.
 */
@Component
public class QueueDepthHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthHealthIndicator.class);

    private final RabbitAdmin rabbitAdmin;
    private final TypeMappingRegistry typeMappingRegistry;

    public QueueDepthHealthIndicator(RabbitAdmin rabbitAdmin, TypeMappingRegistry typeMappingRegistry) {
        this.rabbitAdmin = rabbitAdmin;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean dlqEmpty = true;

            for (TypeMapping mapping : typeMappingRegistry.all()) {
                String rk = mapping.routingKey();
                int dlqDepth = queueDepth(rk + EventTopologyAutoConfiguration.DLQ_SUFFIX, details);
                queueDepth(rk + EventTopologyAutoConfiguration.QUEUE_SUFFIX, details);
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
