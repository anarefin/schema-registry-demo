package com.example.consumer.health;

import com.example.amqp.topology.TopologyNaming;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reports RabbitMQ queue and DLQ message counts (AC-6.1). Derives each handled event's queue
 * ({@code rk.{serviceName}.queue}) and DLQ ({@code rk.{serviceName}.dlq}) from the same
 * {@link HandledEventTypesCache} {@code ServiceQueueTopologyAutoConfiguration} uses to declare
 * them — not every {@link TypeMapping} in the registry, since only handled event types get a
 * per-service queue declared for this service. Iterating the full registry would probe queues
 * this service never declared once a service only handles a subset of event types. Reading from
 * the cache (rather than re-scanning the application context) also means a health probe no
 * longer pays for a full bean/reflection scan on every invocation.
 *
 * <p>Status is DOWN when any DLQ contains messages (signals processing failures). Main-queue depth
 * is reported as info; it never triggers DOWN on its own. Probe failures (broker unreachable,
 * channel error, queue not found) return UNKNOWN with error detail — never UP with a fake zero depth.
 */
@Component
public class QueueDepthHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthHealthIndicator.class);

    private final RabbitAdmin rabbitAdmin;
    private final HandledEventTypesCache handledEventTypesCache;
    private final String serviceName;

    public QueueDepthHealthIndicator(
            RabbitAdmin rabbitAdmin,
            HandledEventTypesCache handledEventTypesCache,
            @Value("${spring.application.name}") String serviceName) {
        this.rabbitAdmin = rabbitAdmin;
        this.handledEventTypesCache = handledEventTypesCache;
        this.serviceName = serviceName;
    }

    /** Exposed so tests can verify this indicator shares a single {@code HandledEventTypesCache} bean. */
    HandledEventTypesCache handledEventTypesCacheForTest() {
        return handledEventTypesCache;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean dlqEmpty = true;
            boolean probeFailed = false;

            Set<TypeMapping> handledMappings = handledEventTypesCache.handledTypeMappings();
            for (TypeMapping mapping : handledMappings) {
                String rk = mapping.getRoutingKey();
                int dlqDepth = queueDepth(TopologyNaming.serviceDlqName(rk, serviceName), details);
                int mainDepth = queueDepth(TopologyNaming.serviceQueueName(rk, serviceName), details);
                if (dlqDepth < 0 || mainDepth < 0) {
                    probeFailed = true;
                } else if (dlqDepth > 0) {
                    dlqEmpty = false;
                }
            }

            if (probeFailed) {
                return Health.unknown().withDetails(details).build();
            }
            return (dlqEmpty ? Health.up() : Health.down())
                    .withDetails(details).build();
        } catch (Exception e) {
            log.warn("Queue depth health check failed", e);
            return Health.unknown().withException(e).build();
        }
    }

    /**
     * Returns message count, or {@code -1} when the probe fails so callers never treat a
     * query error as an empty queue.
     */
    private int queueDepth(String queueName, Map<String, Object> details) {
        try {
            var info = rabbitAdmin.getQueueInfo(queueName);
            if (info == null) {
                details.put(queueName + ".depth", "error: queue not found");
                return -1;
            }
            int depth = (int) info.getMessageCount();
            details.put(queueName + ".depth", depth);
            return depth;
        } catch (Exception e) {
            details.put(queueName + ".depth", "error: " + e.getMessage());
            return -1;
        }
    }
}
