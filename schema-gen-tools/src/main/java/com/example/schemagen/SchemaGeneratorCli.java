package com.example.schemagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Build-time entry point invoked by {@code exec-maven-plugin} at a contracts module's own
 * {@code process-classes} phase. Not part of any public API and never on a service classpath.
 *
 * <p>Accepts a schemas output directory, a compiled classes directory, and a base package to scan
 * for {@code @GenerateSchema}-annotated types, e.g.
 * {@code /abs/path/schemas /abs/path/target/classes com.example.contracts.customers}. The output
 * filename for each class is derived via {@link SchemaFileNaming}.
 */
public final class SchemaGeneratorCli {

    private SchemaGeneratorCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Expected: <schemasOutputDir> <classesDir> <basePackage>");
        }
        Path outputDir = Paths.get(args[0]);
        Path classesDir = Paths.get(args[1]);
        String basePackage = args[2];

        List<Class<?>> eventTypes = GenerateSchemaScanner.findAnnotatedTypes(classesDir, basePackage);
        // Fail the build before writing anything if any event record breaks the paired-annotation
        // contract (shape, blank attribute, or duplicate coordinates/bean name).
        List<EventMappingMetadata> mappings = EventMappingValidator.validate(eventTypes);

        for (Class<?> eventType : eventTypes) {
            Path outputPath = outputDir.resolve(SchemaFileNaming.toFileName(eventType.getSimpleName()));
            write(eventType, outputPath);
        }

        // Index lives only under build output — never committed under src/main/resources.
        Path indexPath = EventMappingIndexWriter.write(classesDir, mappings);
        System.out.println("Event mapping index written: " + indexPath.toAbsolutePath().normalize()
                + " (" + mappings.size() + " mappings)");
    }

    private static void write(Class<?> eventType, Path outputPath) throws IOException {
        String schema = SchemaGenerator.generate(eventType);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
        System.out.println("Schema written: " + eventType.getSimpleName() + " -> " + outputPath.toAbsolutePath().normalize());
    }
}
