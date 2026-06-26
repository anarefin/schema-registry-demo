package com.example.contracts.payments.codefirst;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDeterminismTest {

    @Test
    void generatingTwiceProducesByteIdenticalOutput() throws IOException {
        String first = SchemaGenerator.generateSchema();
        String second = SchemaGenerator.generateSchema();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void committedFileMatchesFreshGeneration() throws IOException {
        String fresh = SchemaGenerator.generateSchema();
        // Surefire sets cwd to the module root (payment-contracts/).
        Path committed = Paths.get("src/main/resources/schemas/payment-processed.schema.json");
        String onDisk = Files.readString(committed, StandardCharsets.UTF_8);
        assertThat(fresh)
                .as("Generated schema differs from committed file — run process-classes and re-commit")
                .isEqualTo(onDisk);
    }
}
