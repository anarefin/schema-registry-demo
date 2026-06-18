package com.example.consumer;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.amqp.EventExchanges;
import com.example.consumer.listener.OrderEventListener;
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
 * Failure-model integration tests (single 5s retry tier collapsed to a fast TTL for the test):
 * <ul>
 *   <li>Poison (deserialization) failure is permanent → DLQ immediately with X-Retry-Count=0
 *       and all X-Failure-* headers populated.</li>
 *   <li>Schema-not-found is transient → retried up to max-attempts then DLQ with X-Retry-Count=3.</li>
 *   <li>Downstream RuntimeException is transient → retried (4 total deliveries) then DLQ.</li>
 * </ul>
 *
 * <p>RabbitMQ = real Testcontainer; ApicurioClient = mock. Retry TTL shortened to 200ms.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "events.retry.tier.ms=200",
        "events.retry.max-attempts=3"
})
class DlxRoutingIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoBean
    ApicurioClient apicurioClient;

    @MockitoSpyBean
    OrderEventListener orderEventListener;

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

        Mockito.reset(apicurioClient);
        Mockito.reset(orderEventListener);

        when(apicurioClient.fetchByCoordinates(any(SchemaCoordinates.class))).thenReturn(schema);
        when(apicurioClient.fetchByGlobalId(eq(MOCK_GLOBAL_ID), eq(SchemaType.JSON))).thenReturn(schema);
        when(apicurioClient.latestVersion(any(), any())).thenReturn(schema);

        drainQueue(OrderEventRouting.DLQ_NAME);
    }

    /**
     * Invalid JSON bytes → DeserializationException (permanent) → DLQ immediately with
     * X-Retry-Count=0 and all X-Failure-* headers populated.
     */
    @Test
    void deserializationPoisonRoutesToDlqWithHeaders() {
        sendRaw(EventExchanges.EVENTS_EXCHANGE, OrderEventRouting.ROUTING_KEY,
                "{INVALID_JSON".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());

        Message dlqMsg = awaitDlq(OrderEventRouting.DLQ_NAME);

        assertThat(SchemaMessageHeaders.getRetryCount(dlqMsg.getMessageProperties())).isEqualTo(0);

        MessageProperties p = dlqMsg.getMessageProperties();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_REASON)).isNotBlank();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_MESSAGE)).isNotBlank();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_STACK_TRACE)).isNotBlank();
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_ROUTING_KEY))
                .isEqualTo(OrderEventRouting.ROUTING_KEY);
        assertThat(headerStr(p, SchemaMessageHeaders.FAILURE_FAILED_AT)).isNotBlank();
    }

    /**
     * Every fetch throws SchemaNotFoundException (classified RETRY) → retried per the single
     * 5s tier → DLQ with X-Retry-Count=3 after max attempts.
     */
    @Test
    void schemaNotFoundRetriedThenDlq() {
        when(apicurioClient.fetchByGlobalId(eq(MOCK_GLOBAL_ID), eq(SchemaType.JSON)))
                .thenThrow(new SchemaNotFoundException("globalId=" + MOCK_GLOBAL_ID));
        when(apicurioClient.fetchByCoordinates(any()))
                .thenThrow(new SchemaNotFoundException("coordinates"));

        sendRaw(EventExchanges.EVENTS_EXCHANGE, OrderEventRouting.ROUTING_KEY,
                "{}".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());

        Message dlqMsg = awaitDlq(OrderEventRouting.DLQ_NAME, 15);
        assertThat(dlqMsg.getMessageProperties()
                .<Integer>getHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT)).isEqualTo(3);
    }

    /**
     * Downstream RuntimeException → retried (4 total deliveries = initial + 3 retries) then DLQ
     * with X-Retry-Count=3.
     */
    @Test
    void downstreamErrorRetriedThenDlq() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            callCount.incrementAndGet();
            throw new RuntimeException("simulated downstream error");
        }).when(orderEventListener).onOrderCreated(any());

        byte[] validJson = objectMapper.writeValueAsBytes(validOrder());
        sendRaw(EventExchanges.EVENTS_EXCHANGE, OrderEventRouting.ROUTING_KEY,
                validJson, UUID.randomUUID().toString());

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(callCount.get()).isGreaterThanOrEqualTo(4));

        Message dlqMsg = awaitDlq(OrderEventRouting.DLQ_NAME);
        assertThat(dlqMsg.getMessageProperties()
                .<Integer>getHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT)).isEqualTo(3);
    }

    // ---- helpers -----------------------------------------------------------

    private void sendRaw(String exchange, String routingKey, byte[] body, String messageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(SchemaType.JSON.contentType());
        props.setHeader(SchemaMessageHeaders.GLOBAL_ID, MOCK_GLOBAL_ID);
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
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

    private static OrderCreated validOrder() {
        return new OrderCreated()
                .withOrderId(UUID.randomUUID().toString())
                .withCustomerId("cust-1")
                .withProductId("prod-A")
                .withQuantity(2)
                .withTotalAmount(19.99)
                .withCurrency("USD")
                .withCreatedAt("2026-05-31T00:00:00Z");
    }

    private static byte[] loadSchemaBytes() throws Exception {
        try (var stream = DlxRoutingIT.class.getClassLoader()
                .getResourceAsStream("schemas/order-created.json")) {
            return Objects.requireNonNull(stream, "order-created.json not on classpath")
                    .readAllBytes();
        }
    }

    /** Safe header-to-string: avoids generic inference picking String.valueOf(char[]) overload. */
    private static String headerStr(MessageProperties props, String key) {
        Object v = props.getHeader(key);
        return v != null ? v.toString() : null;
    }
}
