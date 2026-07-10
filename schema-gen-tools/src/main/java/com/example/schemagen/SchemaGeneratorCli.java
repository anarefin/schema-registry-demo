package com.example.schemagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Build-time entry point invoked by {@code exec-maven-plugin} at a contracts module's own
 * {@code process-classes} phase. Not part of any public API and never on a service classpath.
 *
 * <p>Accepts an output directory followed by one or more event FQCNs, e.g.
 * {@code /abs/path/schemas com.example.contracts.orders.OrderCreated ...}. The output filename for
 * each class is derived via {@link SchemaFileNaming}. Callers pass an absolute directory
 * (typically built from {@code ${project.basedir}}), so no working-directory assumptions are made
 * here.
 */
public final class SchemaGeneratorCli {

    private SchemaGeneratorCli() {}

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected: <outputDir> <FQCN> [<FQCN> ...]");
        }
        Path outputDir = Paths.get(args[0]);
        for (int i = 1; i < args.length; i++) {
            Class<?> eventType = Class.forName(args[i]);
            Path outputPath = outputDir.resolve(SchemaFileNaming.toFileName(eventType.getSimpleName()));
            write(eventType, outputPath);
        }
    }

    private static void write(Class<?> eventType, Path outputPath) throws IOException {
        String schema = SchemaGenerator.generate(eventType);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
        System.out.println("Schema written: " + eventType.getSimpleName() + " -> " + outputPath.toAbsolutePath().normalize());
    }
}
