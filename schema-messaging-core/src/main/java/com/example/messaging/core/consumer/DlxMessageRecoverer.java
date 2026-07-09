package com.example.messaging.core.consumer;

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

    public DlxMessageRecoverer(
            EventConsumerSupport consumerSupport,
            RabbitTemplate rabbitTemplate,
            long[] retryDelaysMs) {
        this.consumerSupport = consumerSupport;
        this.rabbitTemplate = rabbitTemplate;
        this.retryDelaysMs = retryDelaysMs;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        Exception ex = cause instanceof Exception e ? e : new RuntimeException(cause);
        RoutingDecision decision = consumerSupport.classify(ex);

        MessageProperties props = message.getMessageProperties();
        int retryCount = SchemaMessageHeaders.getRetryCount(props);
        String originalRoutingKey = props.getReceivedRoutingKey() != null
                ? props.getReceivedRoutingKey() : "unknown";
        String receivedExchange = props.getReceivedExchange();
        String dlxExchange = receivedExchange.replaceFirst("\\.exchange$", ".dlx");
        String retryExchange = receivedExchange.replaceFirst("\\.exchange$", ".retry.exchange");

        if (decision == RoutingDecision.DLQ_DIRECT || retryCount >= retryDelaysMs.length) {
            consumerSupport.populateFailureHeaders(message, ex, RoutingDecision.DLQ_DIRECT);
            rabbitTemplate.send(dlxExchange, originalRoutingKey, message);
            log.error("→ DLQ exchange={} routingKey={} retries={} cause={}",
                    dlxExchange, originalRoutingKey, retryCount, ex.getMessage());
        } else {
            props.setHeader(SchemaMessageHeaders.RETRY_COUNT, retryCount + 1);
            String retryRoutingKey = originalRoutingKey + ".retry." + tierSuffix(retryCount);
            rabbitTemplate.send(retryExchange, retryRoutingKey, message);
            log.warn("→ retry exchange={} routingKey={} retryCount={} ttlMs={}",
                    retryExchange, retryRoutingKey, retryCount + 1, retryDelaysMs[retryCount]);
        }
    }

    private String tierSuffix(int tier) {
        return switch (tier) {
            case 0 -> "5s";
            case 1 -> "30s";
            case 2 -> "5m";
            default -> "t" + tier;
        };
    }
}
