package com.example.messaging.core.converter;

import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.MissingSchemaHeadersException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.UnknownSchemaArtifactException;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.messaging.core.schema.LocalSchemaCatalog;
import com.example.messaging.core.serde.SerializationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;
import java.util.Objects;

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

    @Mock private LocalSchemaCatalog localSchemaCatalog;
    @Mock private SerializationStrategy strategy;

    private SchemaAwareMessageConverter converter;

    private static final byte[] PAYLOAD_BYTES = "serialized".getBytes();
    private static final SchemaCoordinates COORDS =
            new SchemaCoordinates("events.orders", "OrderCreated");
    private static final ResolvedSchema SCHEMA =
            new ResolvedSchema(COORDS, SchemaType.JSON, "{}".getBytes());

    // A minimal stand-in for a domain object
    static final class Order {
        private final String id;

        Order(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Order other)) {
                return false;
            }
            return Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public String toString() {
            return "Order[id=" + id + "]";
        }
    }

    @BeforeEach
    void setUp() {
        when(strategy.schemaType()).thenReturn(SchemaType.JSON);
        lenient().when(strategy.contentType()).thenReturn(SchemaType.JSON.contentType());
        when(localSchemaCatalog.get(COORDS)).thenReturn(SCHEMA);

        TypeMapping mapping = new TypeMapping(Order.class, COORDS, SchemaType.JSON, "orders.created", "events.orders.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));

        converter = new SchemaAwareMessageConverter(registry, localSchemaCatalog, List.of(strategy));
    }

    /** Constructor eagerly warms each mapping's schema so malformed content fails fast at startup. */
    @Test
    void constructor_warmsEachMappingsSchema() {
        verify(strategy).warm(SCHEMA);
    }

    /** TC-1.9: object → message → object round-trip produces equal payload. */
    @Test
    void roundTrip_objectToMessageToObject() throws Exception {
        Order order = new Order("ord-1");

        when(strategy.serialize(order, SCHEMA)).thenReturn(PAYLOAD_BYTES);
        when(strategy.deserialize(PAYLOAD_BYTES, Order.class, SCHEMA)).thenReturn(order);

        Message msg = converter.toMessage(order, new MessageProperties());
        Object result = converter.fromMessage(msg);

        assertThat(result).isEqualTo(order);
    }

    /** TC-1.10: toMessage populates all required X-Schema-* headers. */
    @Test
    void toMessage_populatesAllSchemaHeaders() throws Exception {
        Order order = new Order("ord-2");
        when(strategy.serialize(any(), any())).thenReturn(PAYLOAD_BYTES);

        Message msg = converter.toMessage(order, new MessageProperties());
        MessageProperties props = msg.getMessageProperties();

        assertThat((Object) props.getHeader(SchemaMessageHeaders.GROUP_ID)).isEqualTo("events.orders");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.ARTIFACT_ID)).isEqualTo("OrderCreated");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.TYPE)).isEqualTo("JSON");
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.CORRELATION_ID)).isNotNull();
    }

    /**
     * TC-1.11: X-Schema-Type header says PROTOBUF (e.g. a stale producer) but the TypeMapping
     * is JSON → IncompatibleSchemaTypeException. The raw header string is compared, so even
     * types this library no longer supports surface as a crisp mismatch. The check runs
     * before schema lookup.
     */
    @Test
    void fromMessage_schemaTipeMismatch_throwsIncompatible() {
        MessageProperties props = new MessageProperties();
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
        when(strategy.serialize(any(), any())).thenThrow(new SchemaValidationException(COORDS.toString(), "required field missing"));

        assertThatThrownBy(() -> converter.toMessage(order, new MessageProperties()))
                .isInstanceOf(SchemaValidationException.class);
        // strategy.serialize was called (and threw) — no Message was constructed
    }

    /** TC-1.13: unparseable bytes in fromMessage throw DeserializationException. */
    @Test
    void fromMessage_badBytes_throwsDeserialization() throws Exception {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");

        when(strategy.deserialize(any(), any(), any()))
                .thenThrow(new com.example.messaging.core.exception.DeserializationException("Order", new RuntimeException("bad bytes")));

        Message msg = new Message(new byte[]{0x00, 0x01}, props);

        assertThatThrownBy(() -> converter.fromMessage(msg))
                .isInstanceOf(com.example.messaging.core.exception.DeserializationException.class);
    }

    @Test
    void fromMessage_missingGroupId_throwsMissingSchemaHeaders() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");

        assertThatThrownBy(() -> converter.fromMessage(new Message(PAYLOAD_BYTES, props)))
                .isInstanceOf(MissingSchemaHeadersException.class)
                .hasMessageContaining(SchemaMessageHeaders.GROUP_ID);
    }

    @Test
    void fromMessage_missingType_throwsMissingSchemaHeaders() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");

        assertThatThrownBy(() -> converter.fromMessage(new Message(PAYLOAD_BYTES, props)))
                .isInstanceOf(MissingSchemaHeadersException.class)
                .hasMessageContaining(SchemaMessageHeaders.TYPE);
    }

    @Test
    void fromMessage_unknownArtifact_throwsUnknownSchemaArtifact() {
        MessageProperties props = new MessageProperties();
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "DoesNotExist");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");

        assertThatThrownBy(() -> converter.fromMessage(new Message(PAYLOAD_BYTES, props)))
                .isInstanceOf(UnknownSchemaArtifactException.class)
                .hasMessageContaining("DoesNotExist");
    }

    /**
     * TC-1.15: constructor rejects TypeMapping whose SchemaType has no registered strategy.
     * Prevents a silent NPE at message-processing time from becoming a startup-time IllegalStateException.
     */
    @Test
    void constructor_missingStrategy_throwsIllegalState() {
        TypeMapping jsonMapping = new TypeMapping(Order.class, COORDS, SchemaType.JSON, "orders.json", "events.orders.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(jsonMapping));

        // No strategy provided — JSON mapping has no matching strategy
        assertThatThrownBy(() ->
                new SchemaAwareMessageConverter(registry, localSchemaCatalog, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SchemaType.JSON");
    }

    /** Malformed schema content fails at converter construction via strategy.warm (ADR-0004). */
    @Test
    void constructor_malformedSchema_throwsInvalidSchemaDefinition() {
        TypeMapping mapping = new TypeMapping(Order.class, COORDS, SchemaType.JSON, "orders.created", "events.orders.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));
        ResolvedSchema bad = new ResolvedSchema(COORDS, SchemaType.JSON, "{{{".getBytes());
        when(localSchemaCatalog.get(COORDS)).thenReturn(bad);
        doThrow(new com.example.messaging.core.exception.InvalidSchemaDefinitionException(
                COORDS.toString(), "Failed to compile JSON schema: bad"))
                .when(strategy).warm(bad);

        assertThatThrownBy(() ->
                new SchemaAwareMessageConverter(registry, localSchemaCatalog, List.of(strategy)))
                .isInstanceOf(com.example.messaging.core.exception.InvalidSchemaDefinitionException.class);
    }
}
