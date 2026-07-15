package com.example.consumer;

import com.example.contracts.customers.CustomerRegistered;
import com.example.consumer.listener.CustomerEventListener;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.consumer.support.PublisherOwnedExchanges;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

/**
 * TC-2.3 (I) — round-trip: CustomerRegistered flows producer → RabbitMQ → consumer listener.
 * TC-2.4 (I) — message carries content-type: application/json + all X-Schema-* headers.
 * TC-2.5 (I) — extra optional field still deserializes (Jackson tolerance).
 *
 * <p>Schema validation is local (classpath, {@code LocalSchemaCatalog}) — no registry mock needed.
 * RabbitMQ is real — provided by Testcontainers with @ServiceConnection auto-wiring.
 */
@SpringBootTest
@Testcontainers
@Import(PublisherOwnedExchanges.class)
class CustomerRegisteredIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoSpyBean
    CustomerEventListener customerEventListener;

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    SchemaAwareMessageConverter converter;

    // ---- TC-2.3: round-trip ------------------------------------------------

    @Test
    void tc23_roundTrip() {
        CustomerRegistered event = buildEvent("round@trip.com", "Round", "Trip");

        eventPublisher.publish(event);

        ArgumentCaptor<CustomerRegistered> captor = ArgumentCaptor.forClass(CustomerRegistered.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(customerEventListener).onCustomerRegistered(captor.capture()));

        CustomerRegistered received = captor.getValue();
        assertThat(received.customerId()).isEqualTo(event.customerId());
        assertThat(received.email()).isEqualTo("round@trip.com");
        assertThat(received.firstName()).isEqualTo("Round");
        assertThat(received.lastName()).isEqualTo("Trip");
    }

    // ---- TC-2.4: content-type + X-Schema-* headers -------------------------

    @Test
    void tc24_schemaHeaders() {
        CustomerRegistered event = buildEvent("hdr@test.com", "Hdr", "Test");

        Message rawMsg = converter.toMessage(event, new MessageProperties());

        assertThat(rawMsg.getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.GROUP_ID))
                .isEqualTo("events.customers");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.ARTIFACT_ID))
                .isEqualTo("CustomerRegistered");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.TYPE))
                .isEqualTo("JSON");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.CORRELATION_ID))
                .isNotBlank();
    }

    // ---- TC-2.5: Jackson tolerance for extra fields ------------------------

    @Test
    void tc25_extraFieldToleratedByJackson() {
        UUID id = UUID.randomUUID();
        String jsonWithExtra = """
                {
                  "customerId": "%s",
                  "email": "extra@test.com",
                  "firstName": "Extra",
                  "lastName": "Field",
                  "promoCode": "SUMMER2026"
                }
                """.formatted(id);
        byte[] bytes = jsonWithExtra.getBytes(StandardCharsets.UTF_8);

        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.customers");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "CustomerRegistered");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        props.setContentType("application/json");

        Message msg = new Message(bytes, props);
        Object result = converter.fromMessage(msg);

        assertThat(result).isInstanceOf(CustomerRegistered.class);
        CustomerRegistered cr = (CustomerRegistered) result;
        assertThat(cr.customerId()).isEqualTo(id);
        assertThat(cr.email()).isEqualTo("extra@test.com");
    }

    // ---- helpers -----------------------------------------------------------

    private static CustomerRegistered buildEvent(String email, String first, String last) {
        return new CustomerRegistered(UUID.randomUUID(), email, first, last, null,
                Instant.parse("2026-05-31T00:00:00Z"));
    }
}
