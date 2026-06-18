package com.example.messaging.core.converter;

import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.example.messaging.core.registry.SchemaResolver;
import com.example.messaging.core.serde.JsonSchemaStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TC-1.9 round-trip · TC-1.10 all headers · TC-1.11 type-mismatch → exception ·
 * TC-1.12 validation fail → no message · TC-1.13 bad bytes → DeserializationException
 */
@ExtendWith(MockitoExtension.class)
class SchemaAwareMessageConverterTest {

    @Mock private SchemaResolver schemaResolver;
    @Mock private JsonSchemaStrategy strategy;

    private SchemaAwareMessageConverter converter;

    private static final byte[] PAYLOAD_BYTES = "serialized".getBytes();
    private static final SchemaCoordinates COORDS =
            new SchemaCoordinates("events.orders", "OrderCreated", "1");
    private static final ResolvedSchema SCHEMA =
            new ResolvedSchema(42L, SchemaType.JSON, "{}".getBytes());

    // A minimal stand-in for a domain object
    record Order(String id) {}

    @BeforeEach
    void setUp() {
        when(strategy.schemaType()).thenReturn(SchemaType.JSON);
        lenient().when(strategy.contentType()).thenReturn(SchemaType.JSON.contentType());

        TypeMapping mapping = new TypeMapping(Order.class, COORDS, SchemaType.JSON, "orders.created");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        converter = new SchemaAwareMessageConverter(registry, schemaResolver, strategy);
    }

    /** TC-1.9: object → message → object round-trip produces equal payload. */
    @Test
    void roundTrip_objectToMessageToObject() throws Exception {
        Order order = new Order("ord-1");

        when(schemaResolver.resolveByCoordinates(COORDS)).thenReturn(SCHEMA);
        when(strategy.serialize(order, SCHEMA)).thenReturn(PAYLOAD_BYTES);
        when(schemaResolver.resolveByGlobalId(42L, SchemaType.JSON)).thenReturn(SCHEMA);
        when(strategy.deserialize(PAYLOAD_BYTES, Order.class, SCHEMA)).thenReturn(order);

        Message msg = converter.toMessage(order, new MessageProperties());
        Object result = converter.fromMessage(msg);

        assertThat(result).isEqualTo(order);
    }

    /** TC-1.10: toMessage populates all required X-Schema-* headers. */
    @Test
    void toMessage_populatesAllSchemaHeaders() throws Exception {
        Order order = new Order("ord-2");
        when(schemaResolver.resolveByCoordinates(COORDS)).thenReturn(SCHEMA);
        when(strategy.serialize(any(), any())).thenReturn(PAYLOAD_BYTES);

        Message msg = converter.toMessage(order, new MessageProperties());
        MessageProperties props = msg.getMessageProperties();

        assertThat((Object) props.getHeader(SchemaMessageHeaders.GLOBAL_ID)).isNotNull();
        assertThat((Object) props.getHeader(SchemaMessageHeaders.GROUP_ID)).isEqualTo("events.orders");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.ARTIFACT_ID)).isEqualTo("OrderCreated");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.VERSION)).isEqualTo("1");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.TYPE)).isEqualTo("JSON");
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.MESSAGE_ID)).isNotNull();
    }

    /**
     * TC-1.11: X-Schema-Type header says PROTOBUF (e.g. a stale producer) but the TypeMapping
     * is JSON → IncompatibleSchemaTypeException. The raw header string is compared, so even
     * types this library no longer supports surface as a crisp mismatch. The check runs
     * before schema resolution (no stub needed for resolveByGlobalId).
     */
    @Test
    void fromMessage_schemaTipeMismatch_throwsIncompatible() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GLOBAL_ID, 42L);
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "PROTOBUF"); // mismatch — TypeMapping says JSON

        Message msg = new Message(PAYLOAD_BYTES, props);

        assertThatThrownBy(() -> converter.fromMessage(msg))
                .isInstanceOf(IncompatibleSchemaTypeException.class);
    }

    /** TC-1.12: validation failure in toMessage throws SchemaValidationException; no message produced. */
    @Test
    void toMessage_validationFails_throwsAndNoMessageProduced() throws Exception {
        Order order = new Order("ord-3");
        when(schemaResolver.resolveByCoordinates(COORDS)).thenReturn(SCHEMA);
        when(strategy.serialize(any(), any())).thenThrow(new SchemaValidationException(COORDS.toString(), "required field missing"));

        assertThatThrownBy(() -> converter.toMessage(order, new MessageProperties()))
                .isInstanceOf(SchemaValidationException.class);
        // strategy.serialize was called (and threw) — no Message was constructed
    }

    /** TC-1.13: unparseable bytes in fromMessage throw DeserializationException. */
    @Test
    void fromMessage_badBytes_throwsDeserialization() throws Exception {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GLOBAL_ID, 42L);
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");

        when(schemaResolver.resolveByGlobalId(42L, SchemaType.JSON)).thenReturn(SCHEMA);
        when(strategy.deserialize(any(), any(), any()))
                .thenThrow(new com.example.messaging.core.exception.DeserializationException("Order", new RuntimeException("bad bytes")));

        Message msg = new Message(new byte[]{0x00, 0x01}, props);

        assertThatThrownBy(() -> converter.fromMessage(msg))
                .isInstanceOf(com.example.messaging.core.exception.DeserializationException.class);
    }
}
