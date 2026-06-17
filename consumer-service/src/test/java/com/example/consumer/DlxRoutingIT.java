package com.example.consumer;

import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.amqp.EventExchanges;
import com.example.consumer.listener.CustomerEventListener;
import com.example.messaging.core.consumer.IdempotencyFilter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.example.messaging.core.registry.ApicurioClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * TC-5.1 I  — schema-not-found → retried per TTL ladder then DLQ
 * TC-5.2 I  — deserialization poison → DLQ, X-Retry-Count=0
 * TC-5.3 I  — permanent (deserialization) failure → DLQ immediately
 * TC-5.4 I  — registry unavailable (SchemaNotFoundException) → retried then DLQ
 * TC-5.5 I  — downstream RuntimeException → retried 3×, then DLQ
 * TC-5.6 I  — retry increments X-Retry-Count, correct tier
 * TC-5.7 I  — all X-Failure-* DLQ headers present
 * TC-5.8 I  — duplicate X-Message-Id processed once (idempotency)
 *
 * <p>Uses short TTL tiers (200/400/600 ms) for speed.
 * RabbitMQ = real Testcontainer; ApicurioClient = mock.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "events.retry.tier0.ms=200",
        "events.retry.tier1.ms=400",
        "events.retry.tier2.ms=600"
})
class DlxRoutingIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoBean
    ApicurioClient apicurioClient;

    @MockitoSpyBean
    CustomerEventListener customerEventListener;

    @Autowired
    IdempotencyFilter idempotencyFilter;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private static final long MOCK_GLOBAL_ID = 42L;
    private ResolvedSchema schema;

    @BeforeEach
    void setUp() throws Exception {
        byte[] schemaBytes = loadSchemaBytes();
        schema = new ResolvedSchema(MOCK_GLOBAL_ID, SchemaType.JSON, schemaBytes);

        // Reset mock + spy to clean state before every test
        Mockito.reset(apicurioClient);
        Mockito.reset(customerEventListener);

        when(apicurioClient.fetchByCoordinates(any(SchemaCoordinates.class))).thenReturn(schema);
        when(apicurioClient.fetchByGlobalId(eq(MOCK_GLOBAL_ID), eq(SchemaType.JSON))).thenReturn(schema);
        when(apicurioClient.latestVersion(any(), any())).thenReturn(schema);

        // Drain DLQ so each test starts with an empty queue
        drainQueue(CustomerEventRouting.DLQ_NAME);
    }

    // ---- TC-5.2 / TC-5.3 / TC-5.7 ----------------------------------------

    /**
     * TC-5.2 + TC-5.3 + TC-5.7:
     * Invalid JSON bytes → DeserializationException (permanent) → DLQ immediately with
     * X-Retry-Count=0 and all X-Failure-* headers populated.
     */
    @Test
    void tc52_53_57_deserializationPoisonRoutesToDlqWithHeaders() {
        sendRaw(EventExchanges.EVENTS_EXCHANGE, CustomerEventRouting.ROUTING_KEY,
                "{INVALID_JSON".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());

        Message dlqMsg = awaitDlq(CustomerEventRouting.DLQ_NAME);

        // TC-5.2 / TC-5.3: permanent → DLQ, retry count = 0
        assertThat(SchemaMessageHeaders.getRetryCount(dlqMsg.getMessageProperties())).isEqualTo(0);

        // TC-5.7: all X-Failure-* headers present
        // Use headerStr() helper — avoids generic inference picking String.valueOf(char[]) overload
        MessageProperties p = dlqMsg.getMessageProperties();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_REASON)).isNotBlank();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_MESSAGE)).isNotBlank();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_STACK_TRACE)).isNotBlank();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_ROUTING_KEY))
                .isEqualTo(CustomerEventRouting.ROUTING_KEY);
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_FAILED_AT)).isNotBlank();
        assertThat(SchemaMessageHeaders.getRetryCount(p)).isEqualTo(0);
    }

    // ---- TC-5.1 / TC-5.4 --------------------------------------------------

    /**
     * TC-5.1 + TC-5.4: every fetch throws SchemaNotFoundException (classified RETRY) →
     * retried per TTL ladder → DLQ with X-Retry-Count=3 after max retries.
     */
    @Test
    void tc51_54_schemaNotFoundRetriedThenDlq() {
        when(apicurioClient.fetchByGlobalId(eq(MOCK_GLOBAL_ID), eq(SchemaType.JSON)))
                .thenThrow(new SchemaNotFoundException("globalId=" + MOCK_GLOBAL_ID));
        when(apicurioClient.fetchByCoordinates(any()))
                .thenThrow(new SchemaNotFoundException("coordinates"));

        sendRaw(EventExchanges.EVENTS_EXCHANGE, CustomerEventRouting.ROUTING_KEY,
                "{}".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());

        Message dlqMsg = awaitDlq(CustomerEventRouting.DLQ_NAME, 15);
        assertThat(dlqMsg.getMessageProperties()
                .<Integer>getHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT)).isEqualTo(3);
    }

    // ---- TC-5.5 / TC-5.6 --------------------------------------------------

    /**
     * TC-5.5 + TC-5.6: downstream RuntimeException → retried 3× (X-Retry-Count increments)
     * then DLQ with X-Retry-Count=3. Verifies retry count = 4 total listener invocations.
     */
    @Test
    void tc55_56_downstreamErrorRetriedThenDlq() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            callCount.incrementAndGet();
            throw new RuntimeException("simulated downstream error");
        }).when(customerEventListener).onCustomerRegistered(any(), any());

        byte[] validJson = objectMapper.writeValueAsBytes(validCustomer());
        sendRaw(EventExchanges.EVENTS_EXCHANGE, CustomerEventRouting.ROUTING_KEY,
                validJson, UUID.randomUUID().toString());

        // 4 calls = initial delivery + 3 retries via TTL queues
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(callCount.get()).isGreaterThanOrEqualTo(4));

        // TC-5.6: X-Retry-Count=3 in DLQ message
        Message dlqMsg = awaitDlq(CustomerEventRouting.DLQ_NAME);
        assertThat(dlqMsg.getMessageProperties()
                .<Integer>getHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT)).isEqualTo(3);
    }

    // ---- TC-5.8 ------------------------------------------------------------

    /**
     * TC-5.8: same X-Message-Id delivered twice → idempotency guard prevents double-processing.
     *
     * <p>Both messages arrive at the listener (spy invoked 2×) but only the first triggers
     * actual processing — the second returns early in the real method. The test verifies
     * that exactly 2 deliveries arrived and the DLQ is empty (no routing error from either).
     */
    @Test
    void tc58_duplicateMessageIdProcessedOnce() throws Exception {
        String messageId = UUID.randomUUID().toString();
        byte[] validJson = objectMapper.writeValueAsBytes(validCustomer());

        sendRaw(EventExchanges.EVENTS_EXCHANGE, CustomerEventRouting.ROUTING_KEY, validJson, messageId);

        // Wait for the first delivery to be processed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                Mockito.verify(customerEventListener, Mockito.atLeastOnce())
                       .onCustomerRegistered(any(), any()));

        // Send the duplicate (same X-Message-Id)
        sendRaw(EventExchanges.EVENTS_EXCHANGE, CustomerEventRouting.ROUTING_KEY, validJson, messageId);

        // Give the duplicate time to arrive and be handled (returns early via idempotency)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                Mockito.verify(customerEventListener, Mockito.times(2))
                       .onCustomerRegistered(any(), any()));

        // No DLQ message: neither delivery caused a failure routing
        assertThat(rabbitTemplate.receive(CustomerEventRouting.DLQ_NAME, 300)).isNull();
    }

    /**
     * Idempotency must not mark a message processed until handler success, so retries after
     * downstream failure still invoke the handler (not short-circuited as a duplicate).
     */
    @Test
    void idempotencyDoesNotBlockRetryAfterHandlerFailure() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            String messageId = inv.getArgument(1);
            if (idempotencyFilter.alreadyProcessed(messageId)) {
                return null;
            }
            callCount.incrementAndGet();
            throw new RuntimeException("simulated downstream error");
        }).when(customerEventListener).onCustomerRegistered(any(), any());

        byte[] validJson = objectMapper.writeValueAsBytes(validCustomer());
        String messageId = UUID.randomUUID().toString();
        sendRaw(EventExchanges.EVENTS_EXCHANGE, CustomerEventRouting.ROUTING_KEY,
                validJson, messageId);

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(callCount.get()).isGreaterThanOrEqualTo(4));

        Message dlqMsg = awaitDlq(CustomerEventRouting.DLQ_NAME);
        assertThat(dlqMsg.getMessageProperties()
                .<Integer>getHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT)).isEqualTo(3);
        assertThat(idempotencyFilter.alreadyProcessed(messageId)).isFalse();
    }

    // ---- helpers -----------------------------------------------------------

    private void sendRaw(String exchange, String routingKey, byte[] body, String messageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(SchemaType.JSON.contentType());
        props.setHeader(SchemaMessageHeaders.GLOBAL_ID, MOCK_GLOBAL_ID);
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.customers");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "CustomerRegistered");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        props.setHeader(SchemaMessageHeaders.MESSAGE_ID, messageId);
        rabbitTemplate.send(exchange, routingKey, new Message(body, props));
    }

    private Message awaitDlq(String queue) {
        return awaitDlq(queue, 10);
    }

    private Message awaitDlq(String queue, int timeoutSeconds) {
        return await().atMost(timeoutSeconds, TimeUnit.SECONDS)
                .until(() -> rabbitTemplate.receive(queue, 200), Objects::nonNull);
    }

    private void drainQueue(String queue) {
        while (rabbitTemplate.receive(queue, 50) != null) {
            // drain
        }
    }

    private static CustomerRegistered validCustomer() {
        CustomerRegistered c = new CustomerRegistered();
        c.setCustomerId(UUID.randomUUID().toString());
        c.setEmail("test@example.com");
        c.setFirstName("Test");
        c.setLastName("User");
        c.setRegisteredAt("2026-05-31T00:00:00Z");
        return c;
    }

    private static byte[] loadSchemaBytes() throws Exception {
        try (var stream = DlxRoutingIT.class.getClassLoader()
                .getResourceAsStream("schemas/customer-registered.json")) {
            return Objects.requireNonNull(stream, "customer-registered.json not on classpath")
                    .readAllBytes();
        }
    }

    /** Safe header-to-string: avoids generic inference picking String.valueOf(char[]) overload. */
    private static String headerStr(MessageProperties props, String key) {
        Object v = props.getHeader(key);
        return v != null ? v.toString() : null;
    }
}
