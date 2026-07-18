package com.example.amqp.topology.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventMappingIndexReaderTest {

    @Test
    void mergesMultipleIndexResourcesDeDupedAndSorted(@TempDir Path jarA, @TempDir Path jarB) throws Exception {
        IndexClassLoaders.writeIndex(jarA, "com.example.B", "com.example.A");
        // jarB repeats A (de-dupe) and adds C.
        IndexClassLoaders.writeIndex(jarB, "com.example.A", "com.example.C");

        try (URLClassLoader cl = IndexClassLoaders.over(jarA, jarB)) {
            List<String> fqcns = EventMappingIndexReader.readAll(cl);
            assertThat(fqcns).containsExactly("com.example.A", "com.example.B", "com.example.C");
        }
    }

    @Test
    void returnsEmptyWhenNoIndexResourceIsVisible(@TempDir Path emptyRoot) throws Exception {
        try (URLClassLoader cl = IndexClassLoaders.over(emptyRoot)) {
            assertThat(EventMappingIndexReader.readAll(cl)).isEmpty();
        }
    }

    @Test
    void toleratesTrailingNewlineAndAnEmptyIndex(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndexRaw(jar, "\n"); // an empty index writes just a newline
        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThat(EventMappingIndexReader.readAll(cl)).isEmpty();
        }
    }

    @Test
    void treatsZeroByteIndexAsEmptyNotMalformed(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndexRaw(jar, ""); // 0-byte resource
        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThat(EventMappingIndexReader.readAll(cl)).isEmpty();
        }
    }

    @Test
    void rejectsInteriorBlankLine(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndexRaw(jar, "com.example.A\n\ncom.example.B\n");
        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThatThrownBy(() -> EventMappingIndexReader.readAll(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("Malformed line");
        }
    }

    @Test
    void rejectsLineContainingWhitespace(@TempDir Path jar) throws Exception {
        IndexClassLoaders.writeIndexRaw(jar, "com.example.A B\n");
        try (URLClassLoader cl = IndexClassLoaders.over(jar)) {
            assertThatThrownBy(() -> EventMappingIndexReader.readAll(cl))
                    .isInstanceOf(EventMappingRegistrationException.class)
                    .hasMessageContaining("Malformed line");
        }
    }
}
