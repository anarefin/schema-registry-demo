package com.example.consumer.health;

import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.orders.OrderEventRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports RabbitMQ queue and DLQ message counts (T-6.5, AC-6.1).
 *
 * <p>Status is WARNING when either DLQ contains messages (signals processing failures).
 * Main-queue depth is reported as info; never triggers DOWN on its own.
 */
@Component
public class QueueDepthHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthHealthIndicator.class);

    private final RabbitAdmin rabbitAdmin;

    public QueueDepthHealthIndicator(RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            int customersDlqDepth = queueDepth(CustomerEventRouting.DLQ_NAME, details);
            int ordersDlqDepth    = queueDepth(OrderEventRouting.DLQ_NAME, details);
            queueDepth(CustomerEventRouting.QUEUE_NAME, details);
            queueDepth(OrderEventRouting.QUEUE_NAME, details);

            boolean dlqEmpty = customersDlqDepth == 0 && ordersDlqDepth == 0;
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
