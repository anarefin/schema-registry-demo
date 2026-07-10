package com.example.messaging.core.consumer;

import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.MissingSchemaHeadersException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.exception.UnknownSchemaArtifactException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-1.14: table-driven test — each taxonomy exception maps to the correct routing decision.
 */
class EventConsumerSupportTest {

    private final EventConsumerSupport support = new EventConsumerSupport();

    static Stream<Arguments> exceptionToDecision() {
        return Stream.of(
            // PERMANENT — straight to DLQ, no retry
            Arguments.of(new SchemaValidationException("coords", "detail"),  RoutingDecision.DLQ_DIRECT),
            Arguments.of(new DeserializationException("ctx", new RuntimeException()), RoutingDecision.DLQ_DIRECT),
            Arguments.of(new SerializationException("ctx", new RuntimeException()),   RoutingDecision.DLQ_DIRECT),
            Arguments.of(new IncompatibleSchemaTypeException("JSON", "PROTOBUF", "c"), RoutingDecision.DLQ_DIRECT),
            Arguments.of(new MissingSchemaHeadersException("X-Schema-GroupId is required"), RoutingDecision.DLQ_DIRECT),
            Arguments.of(new UnknownSchemaArtifactException("events.orders", "Unknown"), RoutingDecision.DLQ_DIRECT),
            // Conservative default for unknown exceptions (e.g. downstream handler failure)
            Arguments.of(new RuntimeException("unexpected"),                 RoutingDecision.RETRY)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("exceptionToDecision")
    void classify_eachExceptionType_mapsToExpectedDecision(Exception ex, RoutingDecision expected) {
        RoutingDecision actual = support.classify(ex);
        assertThat(actual)
                .as("classify(%s)", ex.getClass().getSimpleName())
                .isEqualTo(expected);
    }

    @Test
    void populateFailureHeaders_setsAllFailureHeaders() {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("orders.created");
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, 2);
        Message message = new Message(new byte[0], props);

        support.populateFailureHeaders(
                message, new SchemaValidationException("c", "missing field"), RoutingDecision.DLQ_DIRECT);

        assertThat((Object) props.getHeader(SchemaMessageHeaders.FAILURE_REASON)).isEqualTo("DLQ_DIRECT");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.FAILURE_MESSAGE)).asString().contains("missing field");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.FAILURE_STACK_TRACE)).asString().isNotBlank();
        assertThat((Object) props.getHeader(SchemaMessageHeaders.FAILURE_ROUTING_KEY)).isEqualTo("orders.created");
        assertThat((Object) props.getHeader(SchemaMessageHeaders.FAILURE_FAILED_AT)).asString().isNotBlank();
        assertThat((Object) props.getHeader(SchemaMessageHeaders.FAILURE_RETRY_COUNT)).isEqualTo(2);
    }

    @Test
    void populateFailureHeaders_truncatesStackTraceTo4Kb() {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("orders.created");
        Message message = new Message(new byte[0], props);

        String longMsg = "x".repeat(5_000);
        Exception deep = new RuntimeException(longMsg);
        for (int i = 0; i < 50; i++) {
            deep = new RuntimeException("layer-" + i + "-" + longMsg, deep);
        }

        support.populateFailureHeaders(message, deep, RoutingDecision.RETRY);

        String stack = props.getHeader(SchemaMessageHeaders.FAILURE_STACK_TRACE).toString();
        byte[] bytes = stack.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(bytes.length).isLessThanOrEqualTo(4 * 1024 + "...[truncated]".getBytes().length);
        assertThat(stack).endsWith("...[truncated]");
    }
}
