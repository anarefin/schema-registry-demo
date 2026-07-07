package com.example.schemagen;

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
 * T-1.4: the local mirror of the CI drift gate (spec D1/D5). Iterates {@link GeneratedSchemas#ALL}
 * and asserts, for every event type, that (1) generation is byte-stable and (2) the committed
 * schema on disk equals a fresh generation. A drifted or stale committed file fails the build here
 * before it can reach the registry.
 *
 * <p>Surefire runs with the module directory ({@code schema-gen-tools/}) as the working directory,
 * so the {@code ../<contract-module>/...} relative paths in {@code GeneratedSchemas.ALL} resolve
 * exactly as they do for the {@code exec-maven-plugin} generation run.
 */
class SchemaDeterminismTest {

    static List<GeneratedSchemas.Target> targets() {
        return GeneratedSchemas.ALL;
    }

    @ParameterizedTest(name = "generation is byte-stable: {0}")
    @MethodSource("targets")
    void generatingTwiceProducesByteIdenticalOutput(GeneratedSchemas.Target target) {
        String first = SchemaGenerator.generate(target.eventType());
        String second = SchemaGenerator.generate(target.eventType());
        assertThat(second)
                .as("Non-deterministic generation for %s", target.eventType().getSimpleName())
                .isEqualTo(first);
    }

    @ParameterizedTest(name = "committed file matches fresh generation: {0}")
    @MethodSource("targets")
    void committedFileMatchesFreshGeneration(GeneratedSchemas.Target target) throws IOException {
        String fresh = SchemaGenerator.generate(target.eventType());
        Path committed = Paths.get(target.relativePath());
        String onDisk = Files.readString(committed, StandardCharsets.UTF_8);
        assertThat(fresh)
                .as("Committed schema %s differs from fresh generation — run "
                        + "'./mvnw -pl schema-gen-tools process-classes' and re-commit", committed)
                .isEqualTo(onDisk);
    }
}
