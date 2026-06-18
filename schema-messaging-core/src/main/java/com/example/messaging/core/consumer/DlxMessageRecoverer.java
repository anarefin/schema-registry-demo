package com.example.messaging.core.consumer;

import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/**
 * Routes failed messages to a single-tier AMQP TTL retry queue or straight to the DLQ
 * (spec §9/§11, minimal cut).
 *
 * <p>Decision table:
 * <ul>
 *   <li>Permanent failure (validation, deserialization, type-mismatch) → dlxExchange immediately.</li>
 *   <li>Transient failure with {@code X-Retry-Count} &lt; maxAttempts → retryExchange with
 *       routing key {@code <original-routing-key>.retry.5s} (the single 5s TTL queue).</li>
 *   <li>Transient failure with {@code X-Retry-Count} ≥ maxAttempts → dlxExchange.</li>
 * </ul>
 *
 * <p>The original POC had a 3-tier 5s/30s/5m ladder; the minimal cut keeps a single 5s tier
 * cycled up to {@code maxAttempts} times.
 */
public class DlxMessageRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DlxMessageRecoverer.class);

    /** Routing-key suffix of the single retry queue. Must match the queue declared in the contract topology. */
    public static final String RETRY_SUFFIX = "5s";

    private final EventConsumerSupport consumerSupport;
    private final RabbitTemplate rabbitTemplate;
    private final String dlxExchange;
    private final String retryExchange;
    private final long retryDelayMs;
    private final int maxAttempts;

    public DlxMessageRecoverer(
            EventConsumerSupport consumerSupport,
            RabbitTemplate rabbitTemplate,
            String dlxExchange,
            String retryExchange,
            long retryDelayMs,
            int maxAttempts) {
        this.consumerSupport = consumerSupport;
        this.rabbitTemplate = rabbitTemplate;
        this.dlxExchange = dlxExchange;
        this.retryExchange = retryExchange;
        this.retryDelayMs = retryDelayMs;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        Exception ex = cause instanceof Exception e ? e : new RuntimeException(cause);
        RoutingDecision decision = consumerSupport.classify(ex);

        MessageProperties props = message.getMessageProperties();
        int retryCount = SchemaMessageHeaders.getRetryCount(props);
        String originalRoutingKey = props.getReceivedRoutingKey() != null
                ? props.getReceivedRoutingKey() : "unknown";

        if (decision == RoutingDecision.DLQ_DIRECT || retryCount >= maxAttempts) {
            consumerSupport.populateFailureHeaders(message, ex, RoutingDecision.DLQ_DIRECT);
            rabbitTemplate.send(dlxExchange, originalRoutingKey, message);
            log.error("→ DLQ exchange={} routingKey={} retries={} cause={}",
                    dlxExchange, originalRoutingKey, retryCount, ex.getMessage());
        } else {
            props.setHeader(SchemaMessageHeaders.RETRY_COUNT, retryCount + 1);
            String retryRoutingKey = originalRoutingKey + ".retry." + RETRY_SUFFIX;
            rabbitTemplate.send(retryExchange, retryRoutingKey, message);
            log.warn("→ retry exchange={} routingKey={} retryCount={} ttlMs={}",
                    retryExchange, retryRoutingKey, retryCount + 1, retryDelayMs);
        }
    }

}
