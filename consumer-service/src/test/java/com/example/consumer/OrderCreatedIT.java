package com.example.consumer;

import com.example.contracts.orders.OrderCreated;
import com.example.consumer.listener.OrderEventListener;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.consumer.support.PublisherOwnedExchanges;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

/**
 * TC-3.3 (I) — round-trip: OrderCreated flows producer → RabbitMQ → consumer listener.
 * TC-3.4 (I) — message carries content-type: application/json + all X-Schema-* headers.
 * TC-3.5 (I) — consume path resolves the schema via group/artifact/type headers.
 *
 * <p>Schema validation is local (classpath, {@code LocalSchemaCatalog}) — no registry mock needed.
 * RabbitMQ is real — Testcontainers with @ServiceConnection auto-wiring.
 */
@SpringBootTest
@Testcontainers
@Import(PublisherOwnedExchanges.class)
class OrderCreatedIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoSpyBean
    OrderEventListener orderEventListener;

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    SchemaAwareMessageConverter converter;

    @Autowired
    ObjectMapper objectMapper;

    // ---- TC-3.3: round-trip ------------------------------------------------

    @Test
    void tc33_roundTrip() {
        OrderCreated event = buildEvent(3, new BigDecimal("99.99"), "USD");

        eventPublisher.publish(event);

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
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.GROUP_ID))
                .isEqualTo("events.orders");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.ARTIFACT_ID))
                .isEqualTo("OrderCreated");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.TYPE))
                .isEqualTo("JSON");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.CORRELATION_ID))
                .isNotBlank();
    }

    // ---- TC-3.5: consume by group/artifact/type headers --------------------

    @Test
    void tc35_consumeByGroupArtifactHeaders() throws Exception {
        OrderCreated original = buildEvent(5, new BigDecimal("149.95"), "GBP");
        byte[] jsonBytes = objectMapper.writeValueAsBytes(original);

        MessageProperties props = new MessageProperties();
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
    }

    // ---- helpers -----------------------------------------------------------

    private static OrderCreated buildEvent(int quantity, BigDecimal totalAmount, String currency) {
        return new OrderCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                quantity, totalAmount, currency, Instant.parse("2026-05-31T00:00:00Z"));
    }
}
