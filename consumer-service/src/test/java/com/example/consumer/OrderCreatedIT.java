package com.example.consumer;

import com.example.contracts.orders.OrderCreated;
import com.example.consumer.listener.OrderEventListener;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.messaging.core.registry.ApicurioClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-3.3 (I) — round-trip: OrderCreated flows producer → RabbitMQ → consumer listener.
 * TC-3.4 (I) — message carries content-type: application/json + all X-Schema-* headers.
 * TC-3.5 (I) — X-Schema-GlobalId present; consumer resolves via fetchByGlobalId (skip coordinate lookup).
 *
 * <p>ApicurioClient is mocked — no live registry needed.
 * RabbitMQ is real — Testcontainers with @ServiceConnection auto-wiring.
 */
@SpringBootTest
@Testcontainers
class OrderCreatedIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoBean
    ApicurioClient apicurioClient;

    @MockitoSpyBean
    OrderEventListener orderEventListener;

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    SchemaAwareMessageConverter converter;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final long MOCK_GLOBAL_ID = 99L;

    @BeforeEach
    void mockRegistry() throws Exception {
        byte[] schemaBytes = loadJsonSchema();
        ResolvedSchema schema = new ResolvedSchema(MOCK_GLOBAL_ID, SchemaType.JSON, schemaBytes);

        when(apicurioClient.fetchByCoordinates(any(SchemaCoordinates.class))).thenReturn(schema);
        when(apicurioClient.fetchByGlobalId(eq(MOCK_GLOBAL_ID), eq(SchemaType.JSON))).thenReturn(schema);
        when(apicurioClient.latestVersion(any(), any())).thenReturn(schema);
    }

    // ---- TC-3.3: round-trip ------------------------------------------------

    /**
     * TC-3.3 (I): OrderCreated published via EventPublisher reaches the listener
     * as a fully typed domain object with correct field values.
     */
    @Test
    void tc33_roundTrip() {
        OrderCreated event = buildEvent("ord-e2e", "cust-1", "prod-A", 3, 99.99, "USD");

        eventPublisher.publish("events.exchange", event);

        ArgumentCaptor<OrderCreated> captor = ArgumentCaptor.forClass(OrderCreated.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(orderEventListener).onOrderCreated(captor.capture()));

        OrderCreated received = captor.getValue();
        assertThat(received.getOrderId()).isEqualTo("ord-e2e");
        assertThat(received.getCustomerId()).isEqualTo("cust-1");
        assertThat(received.getProductId()).isEqualTo("prod-A");
        assertThat(received.getQuantity()).isEqualTo(3);
        assertThat(received.getTotalAmount()).isEqualTo(99.99);
        assertThat(received.getCurrency()).isEqualTo("USD");
    }

    // ---- TC-3.4: content-type + X-Schema-* headers -------------------------

    /**
     * TC-3.4 (I): SchemaAwareMessageConverter produces content-type: application/json
     * and all required X-Schema-* headers (globalId, groupId, artifactId, type, messageId).
     */
    @Test
    void tc34_schemaHeaders() {
        OrderCreated event = buildEvent("ord-hdr", "cust-2", "prod-B", 1, 29.99, "EUR");

        Message rawMsg = converter.toMessage(event, new MessageProperties());

        assertThat(rawMsg.getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(rawMsg.getMessageProperties().<Long>getHeader(SchemaMessageHeaders.GLOBAL_ID))
                .isEqualTo(MOCK_GLOBAL_ID);
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.GROUP_ID))
                .isEqualTo("events.orders");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.ARTIFACT_ID))
                .isEqualTo("OrderCreated");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.TYPE))
                .isEqualTo("JSON");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.VERSION))
                .isNotBlank();
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.MESSAGE_ID))
                .isNotBlank();
    }

    // ---- TC-3.5: X-Schema-GlobalId present → fetchByGlobalId used -----------

    /**
     * TC-3.5 (I): consumer resolves schema via X-Schema-GlobalId fast path,
     * bypassing coordinate lookup (spec §6 / SchemaAwareMessageConverter#resolveSchema).
     * The cache may serve the schema without hitting the client at all; the key assertion
     * is that fetchByCoordinates is NOT called (the globalId path skips coordinate resolution).
     */
    @Test
    void tc35_globalIdResolution() throws Exception {
        // Clear startup/pre-warm invocations so assertions only cover this test's calls.
        org.mockito.Mockito.clearInvocations(apicurioClient);

        OrderCreated original = buildEvent("ord-gid", "cust-3", "prod-C", 5, 149.95, "GBP");
        byte[] jsonBytes = objectMapper.writeValueAsBytes(original);

        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GLOBAL_ID, MOCK_GLOBAL_ID);
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        props.setContentType("application/json");

        Message msg = new Message(jsonBytes, props);
        Object result = converter.fromMessage(msg);

        assertThat(result).isInstanceOf(OrderCreated.class);
        OrderCreated parsed = (OrderCreated) result;
        assertThat(parsed.getOrderId()).isEqualTo("ord-gid");
        assertThat(parsed.getQuantity()).isEqualTo(5);
        assertThat(parsed.getTotalAmount()).isEqualTo(149.95);

        // Verify globalId fast-path: fetchByCoordinates was NOT called for this message.
        // (The SchemaAwareMessageConverter#resolveSchema prefers globalId → coordinate lookup skipped.)
        org.mockito.Mockito.verify(apicurioClient, org.mockito.Mockito.never())
                .fetchByCoordinates(any(SchemaCoordinates.class));
    }

    // ---- helpers -----------------------------------------------------------

    private static OrderCreated buildEvent(String orderId, String customerId, String productId,
                                            int quantity, double totalAmount, String currency) {
        return new OrderCreated()
                .withOrderId(orderId)
                .withCustomerId(customerId)
                .withProductId(productId)
                .withQuantity(quantity)
                .withTotalAmount(totalAmount)
                .withCurrency(currency)
                .withCreatedAt("2026-05-31T00:00:00Z");
    }

    private static byte[] loadJsonSchema() throws Exception {
        try (var stream = Objects.requireNonNull(
                OrderCreatedIT.class.getClassLoader()
                        .getResourceAsStream("schemas/order-created.json"),
                "order-created.json not on test classpath")) {
            return stream.readAllBytes();
        }
    }
}
