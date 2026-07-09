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
 * <p>Accepts one or more explicit {@code FQCN=outputPath} pairs, e.g.
 * {@code com.example.contracts.orders.OrderCreated=/abs/path/order-created.schema.json}. Callers
 * pass absolute paths (typically built from {@code ${project.basedir}}), so no working-directory
 * assumptions are made here.
 */
public final class SchemaGeneratorCli {

    private SchemaGeneratorCli() {}

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected at least one FQCN=outputPath argument");
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Expected FQCN=outputPath, got: " + arg);
            }
            Class<?> eventType = Class.forName(arg.substring(0, eq));
            write(eventType, Paths.get(arg.substring(eq + 1)));
        }
    }

    private static void write(Class<?> eventType, Path outputPath) throws IOException {
        String schema = SchemaGenerator.generate(eventType);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
        System.out.println("Schema written: " + eventType.getSimpleName() + " -> " + outputPath.toAbsolutePath().normalize());
    }
}
