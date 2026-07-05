package com.example.contracts.payments.codefirst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Build-time entry point invoked by exec-maven-plugin. Not part of the public API.
class SchemaGeneratorCli {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: SchemaGeneratorCli <output-path>");
        }
        Path outputPath = Paths.get(args[0]);
        String schema = SchemaGenerator.generateSchema();
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
        System.out.println("Schema written to " + outputPath.toAbsolutePath());
    }
}
