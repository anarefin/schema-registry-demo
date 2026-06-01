package com.example.consumer.amqp;

import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.consumer.RoutingDecision;
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
 *   <li>Permanent failure (validation, deserialization, type-mismatch) → {@code events.dlx} immediately.</li>
 *   <li>Transient failure with {@code X-Retry-Count} &lt; maxRetries → {@code events.retry.exchange}
 *       with routing key {@code <original-routing-key>.retry.<tier>} (TTL queue for that tier).</li>
 *   <li>Transient failure with {@code X-Retry-Count} ≥ maxRetries → {@code events.dlx}.</li>
 * </ul>
 */
public class DlxMessageRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DlxMessageRecoverer.class);

    private final EventConsumerSupport consumerSupport;
    private final RabbitTemplate rabbitTemplate;
    private final String dlxExchange;
    private final String retryExchange;
    private final long[] retryDelaysMs;

    public DlxMessageRecoverer(
            EventConsumerSupport consumerSupport,
            RabbitTemplate rabbitTemplate,
            String dlxExchange,
            String retryExchange,
            long[] retryDelaysMs) {
        this.consumerSupport = consumerSupport;
        this.rabbitTemplate = rabbitTemplate;
        this.dlxExchange = dlxExchange;
        this.retryExchange = retryExchange;
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
