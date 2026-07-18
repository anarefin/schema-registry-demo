package com.example.schemagen;

import com.example.schemagen.mappingfixtures.GoodOrderCreated;
import com.example.schemagen.mappingfixtures.GoodOrderShipped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EventMappingIndexWriterTest {

    @Test
    void writesSortedFqcnIndexUnderMetaInf(@TempDir Path classesDir) throws IOException {
        List<EventMappingMetadata> metadata = EventMappingValidator.validate(
                List.of(GoodOrderShipped.class, GoodOrderCreated.class));

        Path index = EventMappingIndexWriter.write(classesDir, metadata);

        assertThat(index).isEqualTo(classesDir.resolve("META-INF/event-mappings.idx"));
        String content = Files.readString(index, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(
                GoodOrderCreated.class.getName() + "\n"
                        + GoodOrderShipped.class.getName() + "\n");
    }

    @Test
    void atomicallyReplacesStaleIndexLeavingNoTempFiles(@TempDir Path classesDir) throws IOException {
        Path metaInf = classesDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("event-mappings.idx"), "com.example.Stale\n");

        EventMappingIndexWriter.write(classesDir,
                EventMappingValidator.validate(List.of(GoodOrderCreated.class)));

        String content = Files.readString(metaInf.resolve("event-mappings.idx"), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(GoodOrderCreated.class.getName() + "\n");

        try (Stream<Path> entries = Files.list(metaInf)) {
            assertThat(entries).containsExactly(metaInf.resolve("event-mappings.idx"));
        }
    }
}
