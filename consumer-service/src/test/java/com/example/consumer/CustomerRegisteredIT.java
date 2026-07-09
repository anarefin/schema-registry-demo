package com.example.consumer;

import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import com.example.consumer.listener.CustomerEventListener;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.example.messaging.core.publisher.EventPublisher;
import com.example.messaging.core.registry.ApicurioClient;
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

import java.nio.charset.StandardCharsets;
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
 * TC-2.3 (I) — round-trip: CustomerRegistered flows producer → RabbitMQ → consumer listener.
 * TC-2.4 (I) — message carries content-type: application/json + all X-Schema-* headers.
 * TC-2.5 (I) — extra optional field still deserializes (Jackson tolerance).
 *
 * <p>ApicurioClient is mocked so the pipeline tests are self-contained (no live registry needed).
 * RabbitMQ is real — provided by Testcontainers with @ServiceConnection auto-wiring.
 */
@SpringBootTest
@Testcontainers
class CustomerRegisteredIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoBean
    ApicurioClient apicurioClient;

    @MockitoSpyBean
    CustomerEventListener customerEventListener;

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    SchemaAwareMessageConverter converter;

    private static final long MOCK_GLOBAL_ID = 42L;

    @BeforeEach
    void mockRegistry() throws Exception {
        byte[] schemaBytes = loadSchema();
        ResolvedSchema schema = new ResolvedSchema(MOCK_GLOBAL_ID, SchemaType.JSON, schemaBytes);

        when(apicurioClient.fetchByCoordinates(any(SchemaCoordinates.class))).thenReturn(schema);
        when(apicurioClient.fetchByGlobalId(eq(MOCK_GLOBAL_ID), eq(SchemaType.JSON))).thenReturn(schema);
        when(apicurioClient.latestVersion(any(), any())).thenReturn(schema);
    }

    // ---- TC-2.3: round-trip ------------------------------------------------

    @Test
    void tc23_roundTrip() {
        CustomerRegistered event = buildEvent("round@trip.com", "Round", "Trip");

        eventPublisher.publish(CustomerEventRouting.EXCHANGE, event);

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
        assertThat(rawMsg.getMessageProperties().<Long>getHeader(SchemaMessageHeaders.GLOBAL_ID))
                .isEqualTo(MOCK_GLOBAL_ID);
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.GROUP_ID))
                .isEqualTo("events.customers");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.ARTIFACT_ID))
                .isEqualTo("CustomerRegistered");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.TYPE))
                .isEqualTo("JSON");
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.VERSION))
                .isNotBlank();
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
        props.setHeader(SchemaMessageHeaders.GLOBAL_ID, MOCK_GLOBAL_ID);
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

    private static byte[] loadSchema() throws Exception {
        try (var stream = Objects.requireNonNull(
                CustomerRegisteredIT.class.getClassLoader()
                        .getResourceAsStream("schemas/customer-registered.schema.json"),
                "customer-registered.schema.json not on test classpath")) {
            return stream.readAllBytes();
        }
    }
}
