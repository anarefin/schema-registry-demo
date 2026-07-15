package com.example.consumer;

import com.example.contracts.orders.OrderBuyer;
import com.example.contracts.orders.OrderFulfilled;
import com.example.contracts.orders.PaymentDetails;
import com.example.contracts.orders.ShippingAddress;
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
 * Round-trip: OrderFulfilled with three nested objects flows producer → RabbitMQ → consumer
 * listener. Also asserts schema headers and consume-by-headers path.
 */
@SpringBootTest
@Testcontainers
@Import(PublisherOwnedExchanges.class)
class OrderFulfilledIT {

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

    @Test
    void roundTripWithNestedObjects() {
        OrderFulfilled event = buildEvent();

        eventPublisher.publish(event);

        ArgumentCaptor<OrderFulfilled> captor = ArgumentCaptor.forClass(OrderFulfilled.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(orderEventListener).onOrderFulfilled(captor.capture()));

        OrderFulfilled received = captor.getValue();
        assertThat(received.orderId()).isEqualTo(event.orderId());
        assertThat(received.buyer().email()).isEqualTo("buyer@example.com");
        assertThat(received.buyer().displayName()).isEqualTo("Jane Doe");
        assertThat(received.shipping().city()).isEqualTo("London");
        assertThat(received.shipping().countryCode()).isEqualTo("GB");
        assertThat(received.payment().method()).isEqualTo("CARD");
        assertThat(received.payment().amount()).isEqualByComparingTo("149.99");
        assertThat(received.payment().currency()).isEqualTo("GBP");
    }

    @Test
    void schemaHeaders() {
        OrderFulfilled event = buildEvent();

        Message rawMsg = converter.toMessage(event, new MessageProperties());

        assertThat(rawMsg.getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.GROUP_ID))
                .isEqualTo("events.orders");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.ARTIFACT_ID))
                .isEqualTo("OrderFulfilled");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.TYPE))
                .isEqualTo("JSON");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.CORRELATION_ID))
                .isNotBlank();
    }

    @Test
    void consumeByGroupArtifactHeaders() throws Exception {
        OrderFulfilled original = buildEvent();
        byte[] jsonBytes = objectMapper.writeValueAsBytes(original);

        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderFulfilled");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        props.setContentType("application/json");

        Message msg = new Message(jsonBytes, props);
        Object result = converter.fromMessage(msg);

        assertThat(result).isInstanceOf(OrderFulfilled.class);
        OrderFulfilled parsed = (OrderFulfilled) result;
        assertThat(parsed.orderId()).isEqualTo(original.orderId());
        assertThat(parsed.buyer().customerId()).isEqualTo(original.buyer().customerId());
        assertThat(parsed.shipping().line1()).isEqualTo("221B Baker Street");
        assertThat(parsed.payment().method()).isEqualTo("CARD");
    }

    private static OrderFulfilled buildEvent() {
        return new OrderFulfilled(
                UUID.randomUUID(),
                new OrderBuyer(UUID.randomUUID(), "buyer@example.com", "Jane Doe"),
                new ShippingAddress("221B Baker Street", null, "London", "NW1 6XE", "GB"),
                new PaymentDetails("CARD", new BigDecimal("149.99"), "GBP"),
                Instant.parse("2026-06-04T10:00:00Z"));
    }
}
