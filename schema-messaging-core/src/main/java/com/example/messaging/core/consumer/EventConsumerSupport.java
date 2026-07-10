package com.example.messaging.core.consumer;

import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.MissingSchemaHeadersException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.exception.UnknownSchemaArtifactException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Set;

/**
 * Consumer-side support that wraps handler invocation and owns the failure-routing decision
 * per the exception taxonomy (spec §9/§11, T-1.10).
 *
 * <p>Exposes {@link #classify(Exception)} for unit-test coverage (TC-1.14).
 * {@link DlxMessageRecoverer} applies the AMQP retry / DLQ routing on top of this decision.
 */
public class EventConsumerSupport {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerSupport.class);

    private static final int MAX_STACK_TRACE_BYTES = 4 * 1024; // 4KB (spec §11)

    private static final Set<Class<? extends Exception>> PERMANENT_EXCEPTIONS = Set.of(
            SchemaValidationException.class,
            DeserializationException.class,
            SerializationException.class,
            IncompatibleSchemaTypeException.class,
            MissingSchemaHeadersException.class,
            UnknownSchemaArtifactException.class
    );

    /**
     * Classify an exception into a routing decision (TC-1.14, table-driven).
     * Walks the full cause chain so Spring AMQP wrapper exceptions (e.g.
     * {@code ListenerExecutionFailedException}) do not mask permanent failures.
     *
     * <ul>
     *   <li>PERMANENT → {@link RoutingDecision#DLQ_DIRECT}: validation, deserialization,
     *       serialization, type mismatch, missing schema headers, unknown artifact.</li>
     *   <li>TRANSIENT → {@link RoutingDecision#RETRY}: any other exception (conservative
     *       default) — e.g. a downstream handler failure.</li>
     * </ul>
     */
    public RoutingDecision classify(Exception e) {
        Throwable current = e;
        while (current != null) {
            for (Class<? extends Exception> permanentType : PERMANENT_EXCEPTIONS) {
                if (permanentType.isInstance(current)) return RoutingDecision.DLQ_DIRECT;
            }
            current = current.getCause();
        }
        return RoutingDecision.RETRY;
    }

    /**
     * Populate DLQ failure headers on {@code message} (spec §11, T-5.4).
     * Stack trace is truncated to {@value MAX_STACK_TRACE_BYTES} bytes.
     */
    public void populateFailureHeaders(Message message, Exception cause, RoutingDecision decision) {
        MessageProperties props = message.getMessageProperties();
        String routingKey = props.getReceivedRoutingKey() != null
                ? props.getReceivedRoutingKey() : "unknown";
        int retryCount = SchemaMessageHeaders.getRetryCount(props);
        Throwable root = rootCause(cause);

        props.setHeader(SchemaMessageHeaders.FAILURE_REASON, decision.name());
        props.setHeader(SchemaMessageHeaders.FAILURE_MESSAGE, truncate(root.getMessage(), 512));
        props.setHeader(SchemaMessageHeaders.FAILURE_STACK_TRACE, truncate(stackTrace(cause), MAX_STACK_TRACE_BYTES));
        props.setHeader(SchemaMessageHeaders.FAILURE_ROUTING_KEY, routingKey);
        props.setHeader(SchemaMessageHeaders.FAILURE_FAILED_AT, Instant.now().toString());
        props.setHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT, retryCount);

        if (decision == RoutingDecision.DLQ_DIRECT) {
            log.error("Permanent failure [{}] routing=DLQ_DIRECT retries={}: {}",
                    root.getClass().getSimpleName(), retryCount, root.getMessage(), cause);
        } else {
            log.warn("Transient failure [{}] routing=RETRY retries={}: {}",
                    root.getClass().getSimpleName(), retryCount, root.getMessage());
        }
    }

    // ---- private ----------------------------------------------------------

    /**
     * Walks to the deepest cause so DLQ headers/logs surface the actual failure (e.g.
     * {@code SchemaValidationException}) rather than Spring AMQP's generic wrapper
     * ({@code ListenerExecutionFailedException: "Failed to convert message"}).
     */
    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return s;
        return new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8) + "...[truncated]";
    }
}
