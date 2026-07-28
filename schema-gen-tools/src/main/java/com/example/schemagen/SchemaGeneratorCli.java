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
        // Fail the build before writing anything if any event class breaks the paired-annotation
        // contract (shape, blank attribute, or duplicate coordinates/bean name).
        List<EventMappingMetadata> mappings = EventMappingValidator.validate(eventTypes);

        for (Class<?> eventType : eventTypes) {
            writeEventSchema(eventType, outputDir, classesDir);
        }

        System.out.println("Schemas generated and event mappings validated ("
                + mappings.size() + " mappings)");
    }

    /**
     * Writes the generated schema to the committed resources dir <em>and</em> to
     * {@code classesDir/schemas/} so the same {@code process-classes} run refreshes the
     * runtime classpath (Maven already copied {@code src/.../schemas} before this phase).
     */
    static void writeEventSchema(Class<?> eventType, Path committedSchemasDir, Path classesDir)
            throws IOException {
        String fileName = SchemaFileNaming.toFileName(eventType.getSimpleName());
        String schema = SchemaGenerator.generate(eventType);
        write(schema, committedSchemasDir.resolve(fileName));
        write(schema, classesDir.resolve("schemas").resolve(fileName));
        System.out.println("Schema written: " + eventType.getSimpleName()
                + " -> " + committedSchemasDir.resolve(fileName).toAbsolutePath().normalize()
                + " (+ classpath " + classesDir.resolve("schemas").resolve(fileName).toAbsolutePath().normalize()
                + ")");
    }

    private static void write(String schema, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
    }
}
