package com.example.consumer;

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
import java.util.Objects;
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

    /**
     * TC-2.3 (I): CustomerRegistered published via EventPublisher reaches the listener
     * as a fully typed domain object with correct field values.
     */
    @Test
    void tc23_roundTrip() {
        CustomerRegistered event = buildEvent("e2e-id", "round@trip.com", "Round", "Trip");

        eventPublisher.publish("events.exchange", event);

        ArgumentCaptor<CustomerRegistered> captor = ArgumentCaptor.forClass(CustomerRegistered.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(customerEventListener).onCustomerRegistered(captor.capture(), any()));

        CustomerRegistered received = captor.getValue();
        assertThat(received.getCustomerId()).isEqualTo("e2e-id");
        assertThat(received.getEmail()).isEqualTo("round@trip.com");
        assertThat(received.getFirstName()).isEqualTo("Round");
        assertThat(received.getLastName()).isEqualTo("Trip");
    }

    // ---- TC-2.4: content-type + X-Schema-* headers -------------------------

    /**
     * TC-2.4 (I): SchemaAwareMessageConverter produces content-type: application/json
     * and all required X-Schema-* headers (globalId, groupId, artifactId, type, messageId).
     */
    @Test
    void tc24_schemaHeaders() {
        CustomerRegistered event = buildEvent("hdr-id", "hdr@test.com", "Hdr", "Test");

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
        assertThat(rawMsg.getMessageProperties().<String>getHeader(SchemaMessageHeaders.MESSAGE_ID))
                .isNotBlank();
    }

    // ---- TC-2.5: Jackson tolerance for extra fields ------------------------

    /**
     * TC-2.5 (I): a message body with an unknown extra field (e.g. from a v2 producer)
     * deserializes cleanly at a v1 consumer — Jackson tolerates unknown properties.
     */
    @Test
    void tc25_extraFieldToleratedByJackson() {
        String jsonWithExtra = """
                {
                  "customerId": "extra-id",
                  "email": "extra@test.com",
                  "firstName": "Extra",
                  "lastName": "Field",
                  "promoCode": "SUMMER2026"
                }
                """;
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
        assertThat(cr.getCustomerId()).isEqualTo("extra-id");
        assertThat(cr.getEmail()).isEqualTo("extra@test.com");
    }

    // ---- helpers -----------------------------------------------------------

    private static CustomerRegistered buildEvent(String id, String email, String first, String last) {
        CustomerRegistered e = new CustomerRegistered();
        e.setCustomerId(id);
        e.setEmail(email);
        e.setFirstName(first);
        e.setLastName(last);
        return e;
    }

    private static byte[] loadSchema() throws Exception {
        try (var stream = Objects.requireNonNull(
                CustomerRegisteredIT.class.getClassLoader()
                        .getResourceAsStream("schemas/customer-registered.json"),
                "customer-registered.json not on test classpath")) {
            return stream.readAllBytes();
        }
    }
}
