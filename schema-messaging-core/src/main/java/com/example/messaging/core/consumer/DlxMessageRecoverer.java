package com.example.messaging.core.consumer;

import com.example.amqp.topology.TopologyNaming;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/**
 * Routes failed messages to the AMQP TTL retry ladder or DLQ (spec §9/§11 / T-5.2–5.4).
 *
 * <p>Decision table:
 * <ul>
 *   <li>Permanent failure (validation, deserialization, type-mismatch) → dlxExchange immediately.</li>
 *   <li>Transient failure with {@code X-Retry-Count} &lt; maxRetries → retryExchange
 *       with routing key {@code <original-routing-key>.retry.<tier>} (TTL queue for that tier).</li>
 *   <li>Transient failure with {@code X-Retry-Count} ≥ maxRetries → dlxExchange.</li>
 * </ul>
 *
 * <p>The DLX/retry exchange names are no longer fixed configuration — each domain owns its own
 * exchanges (spec: contract-owned-amqp-topology.md), so this recoverer derives them per message
 * from the message's actual received exchange (correct even for messages that already traversed
 * the retry ladder, since retry queues dead-letter back to the main exchange before re-delivery).
 */
public class DlxMessageRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DlxMessageRecoverer.class);

    private final EventConsumerSupport consumerSupport;
    private final RabbitTemplate rabbitTemplate;
    private final long[] retryDelaysMs;
    private final String serviceName;

    public DlxMessageRecoverer(
            EventConsumerSupport consumerSupport,
            RabbitTemplate rabbitTemplate,
            long[] retryDelaysMs,
            String serviceName) {
        this.consumerSupport = consumerSupport;
        this.rabbitTemplate = rabbitTemplate;
        this.retryDelaysMs = retryDelaysMs;
        this.serviceName = serviceName;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        Exception ex = cause instanceof Exception e ? e : new RuntimeException(cause);
        RoutingDecision decision = consumerSupport.classify(ex);

        MessageProperties props = message.getMessageProperties();
        int retryCount = SchemaMessageHeaders.getRetryCount(props);
        String receivedRoutingKey = props.getReceivedRoutingKey() != null
                ? props.getReceivedRoutingKey() : "unknown";
        // A message that already failed once arrives here via its private per-service binding
        // (see EventTopologyFactory.declarablesForEvent), so the received key carries the
        // ".<serviceName>" suffix — strip it back to the plain key before re-deriving the next
        // retry/DLQ routing key, or the suffix would be appended again on every subsequent failure.
        String originalRoutingKey = TopologyNaming.stripServiceRoutingKey(receivedRoutingKey, serviceName);
        String receivedExchange = props.getReceivedExchange();
        if (receivedExchange == null || receivedExchange.isBlank()) {
            throw new IllegalStateException(
                    "Cannot route failure: MessageProperties.receivedExchange is null/blank"
                    + " (routingKey=" + originalRoutingKey + ")");
        }
        String dlxExchange = TopologyNaming.dlxExchangeName(receivedExchange);
        String retryExchange = TopologyNaming.retryExchangeName(receivedExchange);

        if (decision == RoutingDecision.DLQ_DIRECT || retryCount >= retryDelaysMs.length) {
            consumerSupport.populateFailureHeaders(message, ex, RoutingDecision.DLQ_DIRECT);
            String dlqRoutingKey = TopologyNaming.serviceDlqRoutingKey(originalRoutingKey, serviceName);
            rabbitTemplate.send(dlxExchange, dlqRoutingKey, message);
            log.error("→ DLQ exchange={} routingKey={} retries={} cause={}",
                    dlxExchange, dlqRoutingKey, retryCount, ex.getMessage());
        } else {
            props.setHeader(SchemaMessageHeaders.RETRY_COUNT, retryCount + 1);
            String retryRoutingKey = TopologyNaming.serviceRetryRoutingKey(
                    originalRoutingKey, serviceName, retryCount);
            rabbitTemplate.send(retryExchange, retryRoutingKey, message);
            log.warn("→ retry exchange={} routingKey={} retryCount={} ttlMs={}",
                    retryExchange, retryRoutingKey, retryCount + 1, retryDelaysMs[retryCount]);
        }
    }
}
