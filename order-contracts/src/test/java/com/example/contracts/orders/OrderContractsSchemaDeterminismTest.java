package com.example.contracts.orders;

import com.example.schemagen.SchemaGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local mirror of the CI drift gate (spec D1/D5, ADR-0003) for this domain. For every event
 * type owned by order-contracts, asserts that (1) generation is byte-stable and (2) the committed
 * schema on disk equals a fresh generation. A drifted or stale committed file fails the build here
 * before it can reach the registry.
 */
class OrderContractsSchemaDeterminismTest {

    /** (event type -> committed schema file), mirroring this module's exec-maven-plugin arguments. */
    private record Target(Class<?> eventType, String relativePath) {}

    static List<Target> targets() {
        return List.of(
                new Target(OrderCreated.class,   "src/main/resources/schemas/order-created.schema.json"),
                new Target(OrderShipped.class,   "src/main/resources/schemas/order-shipped.schema.json"),
                new Target(OrderCancelled.class, "src/main/resources/schemas/order-cancelled.schema.json"),
                new Target(OrderFulfilled.class, "src/main/resources/schemas/order-fulfilled.schema.json"));
    }

    @ParameterizedTest(name = "generation is byte-stable: {0}")
    @MethodSource("targets")
    void generatingTwiceProducesByteIdenticalOutput(Target target) {
        String first = SchemaGenerator.generate(target.eventType());
        String second = SchemaGenerator.generate(target.eventType());
        assertThat(second)
                .as("Non-deterministic generation for %s", target.eventType().getSimpleName())
                .isEqualTo(first);
    }

    @ParameterizedTest(name = "committed file matches fresh generation: {0}")
    @MethodSource("targets")
    void committedFileMatchesFreshGeneration(Target target) throws IOException {
        String fresh = SchemaGenerator.generate(target.eventType());
        Path committed = Paths.get(target.relativePath());
        String onDisk = Files.readString(committed, StandardCharsets.UTF_8);
        assertThat(fresh)
                .as("Committed schema %s differs from fresh generation — run "
                        + "'./mvnw -pl order-contracts process-classes' and re-commit", committed)
                .isEqualTo(onDisk);
    }
}
