package com.example.amqp.topology.mapping;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test support: materialise {@code META-INF/event-mappings.idx} files into temp "jar root"
 * directories and expose them through a {@link URLClassLoader}, so multi-jar merge behaviour can be
 * exercised without building real jars. The fixture event records live in {@code test-classes}
 * (reachable via the parent loader); only the index resources are synthesised per test.
 */
final class IndexClassLoaders {

    private IndexClassLoaders() {}

    /** Writes {@code lines} joined with '\n' plus a trailing newline into {@code root/META-INF/...}. */
    static Path writeIndex(Path root, String... lines) throws IOException {
        return writeIndexRaw(root, String.join("\n", lines) + (lines.length == 0 ? "" : "\n"));
    }

    /** Writes exact {@code content} (no massaging) as the index under {@code root}. */
    static Path writeIndexRaw(Path root, String content) throws IOException {
        Path index = root.resolve(EventMappingIndexReader.INDEX_RESOURCE_PATH);
        Files.createDirectories(index.getParent());
        Files.writeString(index, content, StandardCharsets.UTF_8);
        return index;
    }

    /**
     * A loader that sees index resources from {@code roots} on top of the current test classpath
     * (which supplies the fixture classes and no competing index resource).
     */
    static URLClassLoader over(Path... roots) {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = toUrl(roots[i]);
        }
        return new URLClassLoader(urls, IndexClassLoaders.class.getClassLoader());
    }

    private static URL toUrl(Path root) {
        try {
            return root.toUri().toURL();
        } catch (IOException e) {
            throw new IllegalStateException("Bad root URL: " + root, e);
        }
    }
}
