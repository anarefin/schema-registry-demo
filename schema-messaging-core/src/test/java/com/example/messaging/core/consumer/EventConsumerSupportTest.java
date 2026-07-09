package com.example.messaging.core.consumer;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.RegistryUnavailableException;
import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
            // TRANSIENT — eligible for retry
            Arguments.of(new SchemaNotFoundException("coords"),              RoutingDecision.RETRY),
            Arguments.of(new RegistryUnavailableException("down"),           RoutingDecision.RETRY),
            // Conservative default for unknown exceptions
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
}
