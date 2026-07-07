package com.example.schemagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Build-time entry point invoked by {@code exec-maven-plugin} at {@code process-classes}. Not part
 * of any public API and never on a service classpath.
 *
 * <p>With no arguments it regenerates every schema in {@link GeneratedSchemas#ALL}. Alternatively
 * it accepts explicit {@code FQCN=outputPath} pairs, e.g.
 * {@code com.example.contracts.orders.OrderCreated=../order-contracts/.../order-created.schema.json}.
 */
public final class SchemaGeneratorCli {

    private SchemaGeneratorCli() {}

    /**
     * Directory the {@link GeneratedSchemas#ALL} relative paths resolve against. Defaults to the
     * process working directory ({@code .}) — correct for Surefire, whose working directory is the
     * {@code schema-gen-tools} module dir. The {@code exec-maven-plugin} runs in-process with the
     * <em>reactor</em> working directory, so it passes {@code -Dschemagen.basedir=${project.basedir}}
     * to keep both paths resolving to the same place inside the repo.
     */
    private static final Path BASE_DIR = Paths.get(System.getProperty("schemagen.basedir", "."));

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        if (args.length == 0) {
            for (GeneratedSchemas.Target target : GeneratedSchemas.ALL) {
                write(target.eventType(), BASE_DIR.resolve(target.relativePath()));
            }
            return;
        }
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Expected FQCN=outputPath, got: " + arg);
            }
            Class<?> eventType = Class.forName(arg.substring(0, eq));
            write(eventType, BASE_DIR.resolve(arg.substring(eq + 1)));
        }
    }

    private static void write(Class<?> eventType, Path outputPath) throws IOException {
        String schema = SchemaGenerator.generate(eventType);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, schema, StandardCharsets.UTF_8);
        System.out.println("Schema written: " + eventType.getSimpleName() + " -> " + outputPath.toAbsolutePath().normalize());
    }
}
