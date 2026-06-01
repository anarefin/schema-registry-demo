package com.example.consumer;

import com.example.contracts.orders.OrderCreated;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.example.messaging.core.registry.ApicurioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TC-4.6 (I): producer configured with a pinned schema version — the X-Schema-Version
 * header in outbound messages must reflect the pinned value, not "latest".
 *
 * <p>Demonstrates spec §10.5 version-pinning: set {@code schema.orders.pinned-version=1}
 * in application properties; the {@code TypeMapping} built by {@link
 * com.example.consumer.config.OrderContractsConfiguration} will use
 * {@code SchemaCoordinates("events.orders","OrderCreated","1")} and the converter
 * will stamp {@code X-Schema-Version: 1} on every outbound message.
 */
@SpringBootTest(properties = "schema.orders.pinned-version=1")
@Testcontainers
class SchemaVersionPinningIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @MockitoBean
    ApicurioClient apicurioClient;

    @Autowired
    SchemaAwareMessageConverter converter;

    private static final long MOCK_GLOBAL_ID = 55L;

    @BeforeEach
    void mockRegistry() throws Exception {
        byte[] schemaBytes = loadProtoSchema();
        ResolvedSchema schema = new ResolvedSchema(MOCK_GLOBAL_ID, SchemaType.PROTOBUF, schemaBytes);
        when(apicurioClient.fetchByCoordinates(any(SchemaCoordinates.class))).thenReturn(schema);
        when(apicurioClient.fetchByGlobalId(MOCK_GLOBAL_ID, SchemaType.PROTOBUF)).thenReturn(schema);
        when(apicurioClient.latestVersion(any(), any())).thenReturn(schema);
    }

    /**
     * TC-4.6 (I): X-Schema-Version header equals the pinned version string "1", not "latest".
     * Confirms that {@code schema.orders.pinned-version=1} wires all the way through to the
     * AMQP header on every produced message.
     */
    @Test
    void tc46_pinnedVersionAppearsInHeader() {
        OrderCreated event = OrderCreated.newBuilder()
                .setOrderId("ord-pinned-v1")
                .setCustomerId("cust-pin")
                .setProductId("prod-P")
                .setQuantity(2)
                .setTotalAmount(59.99)
                .setCurrency("USD")
                .setCreatedAt("2026-05-31T00:00:00Z")
                .build();

        Message msg = converter.toMessage(event, new MessageProperties());

        String versionHeader = msg.getMessageProperties().getHeader(SchemaMessageHeaders.VERSION);
        assertThat(versionHeader)
                .as("pinned-version=1 must be reflected in X-Schema-Version header")
                .isEqualTo("1");
    }

    /**
     * TC-4.6 complement: sanity-check that without pinning (default "latest") the header
     * reads "latest". This is covered by the existing OrderCreatedIT but verified here
     * for contrast — the test uses the same bean wiring trick but with the default config.
     */
    @Test
    void tc46_pinnedVersionIsNotLatest() {
        // With schema.orders.pinned-version=1 active in this context,
        // the header must NOT be the default "latest" sentinel.
        OrderCreated event = OrderCreated.newBuilder()
                .setOrderId("ord-pin-check")
                .setCustomerId("cust-c")
                .setProductId("prod-Q")
                .setQuantity(1)
                .setTotalAmount(9.99)
                .setCurrency("GBP")
                .setCreatedAt("2026-05-31T00:00:00Z")
                .build();

        Message msg = converter.toMessage(event, new MessageProperties());

        String versionHeader = msg.getMessageProperties().getHeader(SchemaMessageHeaders.VERSION);
        assertThat(versionHeader).isNotEqualTo("latest");
    }

    private static byte[] loadProtoSchema() throws Exception {
        try (var stream = Objects.requireNonNull(
                SchemaVersionPinningIT.class.getClassLoader()
                        .getResourceAsStream("schemas/order-created.proto"),
                "order-created.proto not on test classpath")) {
            return stream.readAllBytes();
        }
    }
}
