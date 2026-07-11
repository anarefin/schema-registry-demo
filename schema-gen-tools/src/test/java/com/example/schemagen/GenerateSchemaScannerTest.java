package com.example.schemagen;

import com.example.schemagen.fixtures.MarkedEvent;
import com.example.schemagen.fixtures.nested.NestedMarkedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateSchemaScannerTest {

    @Test
    void findsOnlyAnnotatedTypesRecursivelyUnderBasePackage() throws Exception {
        Path classesDir = testClassesRoot();
        List<Class<?>> found = GenerateSchemaScanner.findAnnotatedTypes(
                classesDir, "com.example.schemagen.fixtures");

        assertThat(found)
                .extracting(Class::getName)
                .containsExactly(
                        MarkedEvent.class.getName(),
                        NestedMarkedEvent.class.getName());
    }

    @Test
    void failsWhenPackageDirectoryMissing(@TempDir Path emptyRoot) {
        assertThatThrownBy(() ->
                        GenerateSchemaScanner.findAnnotatedTypes(
                                emptyRoot, "com.example.missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("package directory missing");
    }

    @Test
    void failsWhenNoAnnotatedTypes(@TempDir Path root) throws IOException {
        Path packageDir = root.resolve("com/example/empty");
        Files.createDirectories(packageDir);
        // A .class-less package dir still yields zero annotated types.
        assertThatThrownBy(() ->
                        GenerateSchemaScanner.findAnnotatedTypes(root, "com.example.empty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No @GenerateSchema types found");
    }

    private static Path testClassesRoot() throws URISyntaxException {
        URL resource = MarkedEvent.class.getProtectionDomain().getCodeSource().getLocation();
        // When running under Surefire, code source is target/test-classes.
        return Path.of(resource.toURI());
    }
}
