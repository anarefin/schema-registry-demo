package com.example.consumer;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
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

    @Autowired
    ObjectMapper objectMapper;

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

    @Test
    void tc33_roundTrip() {
        OrderCreated event = buildEvent(3, new BigDecimal("99.99"), "USD");

        eventPublisher.publish(OrderEventRouting.EXCHANGE, event);

        ArgumentCaptor<OrderCreated> captor = ArgumentCaptor.forClass(OrderCreated.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(orderEventListener).onOrderCreated(captor.capture()));

        OrderCreated received = captor.getValue();
        assertThat(received.orderId()).isEqualTo(event.orderId());
        assertThat(received.customerId()).isEqualTo(event.customerId());
        assertThat(received.productId()).isEqualTo(event.productId());
        assertThat(received.quantity()).isEqualTo(3);
        assertThat(received.totalAmount()).isEqualByComparingTo("99.99");
        assertThat(received.currency()).isEqualTo("USD");
    }

    // ---- TC-3.4: content-type + X-Schema-* headers -------------------------

    @Test
    void tc34_schemaHeaders() {
        OrderCreated event = buildEvent(1, new BigDecimal("29.99"), "EUR");

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
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.CORRELATION_ID))
                .isNotBlank();
    }

    // ---- TC-3.5: X-Schema-GlobalId present → fetchByGlobalId used -----------

    @Test
    void tc35_globalIdResolution() throws Exception {
        org.mockito.Mockito.clearInvocations(apicurioClient);

        OrderCreated original = buildEvent(5, new BigDecimal("149.95"), "GBP");
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
        assertThat(parsed.orderId()).isEqualTo(original.orderId());
        assertThat(parsed.quantity()).isEqualTo(5);
        assertThat(parsed.totalAmount()).isEqualByComparingTo("149.95");

        org.mockito.Mockito.verify(apicurioClient, org.mockito.Mockito.never())
                .fetchByCoordinates(any(SchemaCoordinates.class));
    }

    // ---- helpers -----------------------------------------------------------

    private static OrderCreated buildEvent(int quantity, BigDecimal totalAmount, String currency) {
        return new OrderCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                quantity, totalAmount, currency, Instant.parse("2026-05-31T00:00:00Z"));
    }

    private static byte[] loadJsonSchema() throws Exception {
        try (var stream = Objects.requireNonNull(
                OrderCreatedIT.class.getClassLoader()
                        .getResourceAsStream("schemas/order-created.schema.json"),
                "order-created.schema.json not on test classpath")) {
            return stream.readAllBytes();
        }
    }
}
