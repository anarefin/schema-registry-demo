package com.example.contracts.customers;

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
 * type owned by customer-contracts, asserts that (1) generation is byte-stable and (2) the
 * committed schema on disk equals a fresh generation. A drifted or stale committed file fails the
 * build here before it can reach the registry.
 */
class CustomerContractsSchemaDeterminismTest {

    /** (event type -> committed schema file), mirroring this module's exec-maven-plugin arguments. */
    private static final class Target {
        private final Class<?> eventType;
        private final String relativePath;

        Target(Class<?> eventType, String relativePath) {
            this.eventType = eventType;
            this.relativePath = relativePath;
        }

        Class<?> getEventType() {
            return eventType;
        }

        String getRelativePath() {
            return relativePath;
        }

        @Override
        public String toString() {
            return eventType.getSimpleName();
        }
    }

    static List<Target> targets() {
        return List.of(
                new Target(CustomerRegistered.class,   "src/main/resources/schemas/customer-registered.schema.json"),
                new Target(CustomerAddressAdded.class, "src/main/resources/schemas/customer-address-added.schema.json"),
                new Target(CustomerTierChanged.class,  "src/main/resources/schemas/customer-tier-changed.schema.json"));
    }

    @ParameterizedTest(name = "generation is byte-stable: {0}")
    @MethodSource("targets")
    void generatingTwiceProducesByteIdenticalOutput(Target target) {
        String first = SchemaGenerator.generate(target.getEventType());
        String second = SchemaGenerator.generate(target.getEventType());
        assertThat(second)
                .as("Non-deterministic generation for %s", target.getEventType().getSimpleName())
                .isEqualTo(first);
    }

    @ParameterizedTest(name = "committed file matches fresh generation: {0}")
    @MethodSource("targets")
    void committedFileMatchesFreshGeneration(Target target) throws IOException {
        String fresh = SchemaGenerator.generate(target.getEventType());
        Path committed = Paths.get(target.getRelativePath());
        String onDisk = Files.readString(committed, StandardCharsets.UTF_8);
        assertThat(fresh)
                .as("Committed schema %s differs from fresh generation — run "
                        + "'./mvnw -pl customer-contracts process-classes' and re-commit", committed)
                .isEqualTo(onDisk);
    }
}
