package com.example.schemagen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers event contract types under a compiled {@code classesDir} that carry the
 * {@code @GenerateSchema} marker (matched by FQCN string so this module stays free of a
 * compile dependency on {@code event-contract-kit}).
 */
public final class GenerateSchemaScanner {

    /** FQCN of {@code com.example.amqp.topology.mapping.GenerateSchema} — keep in sync. */
    static final String GENERATE_SCHEMA_ANNOTATION =
            "com.example.amqp.topology.mapping.GenerateSchema";

    private GenerateSchemaScanner() {}

    /**
     * @param classesDir  compiled output root (typically {@code target/classes})
     * @param basePackage package to scan recursively (e.g. {@code com.example.contracts.customers})
     * @return types annotated with {@code @GenerateSchema}, sorted by simple name
     * @throws IllegalStateException if no annotated types are found
     */
    public static List<Class<?>> findAnnotatedTypes(Path classesDir, String basePackage) {
        Path packageDir = classesDir.resolve(basePackage.replace('.', '/'));
        if (!Files.isDirectory(packageDir)) {
            throw new IllegalStateException(
                    "No @GenerateSchema types found: package directory missing: " + packageDir
                            + " (basePackage=" + basePackage + ", classesDir=" + classesDir + ")");
        }

        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.equals("module-info.class") && !name.equals("package-info.class");
                    })
                    .forEach(classFile -> {
                        String fqcn = toFqcn(classesDir, classFile);
                        try {
                            Class<?> type = Class.forName(fqcn);
                            if (hasGenerateSchema(type)) {
                                found.add(type);
                            }
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(
                                    "Failed to load class " + fqcn + " from " + classFile, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + packageDir, e);
        }

        found.sort(Comparator.comparing(Class::getSimpleName));

        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "No @GenerateSchema types found under package " + basePackage
                            + " in classesDir " + classesDir);
        }
        return List.copyOf(found);
    }

    private static String toFqcn(Path classesDir, Path classFile) {
        Path relative = classesDir.relativize(classFile);
        String path = relative.toString().replace('\\', '/');
        if (path.endsWith(".class")) {
            path = path.substring(0, path.length() - ".class".length());
        }
        return path.replace('/', '.');
    }

    private static boolean hasGenerateSchema(Class<?> type) {
        for (Annotation annotation : type.getAnnotations()) {
            if (GENERATE_SCHEMA_ANNOTATION.equals(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }
}
