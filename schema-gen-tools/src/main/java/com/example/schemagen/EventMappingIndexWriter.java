package com.example.schemagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes the deterministic build-time event index to
 * {@code <classesDir>/META-INF/event-mappings.idx}: one FQCN per line, lexically sorted, trailing
 * newline, UTF-8. Written <em>only</em> under the compiled build output ({@code classesDir} is the
 * module's {@code ${project.build.outputDirectory}}) so it ships inside the packaged jar and is
 * never committed under {@code src/main/resources}.
 *
 * <p>The replace is atomic: content is staged in a sibling temp file and {@code ATOMIC_MOVE}-d over
 * any stale index, so a partially-written index is never observable. Determinism (sort + trailing
 * newline) mirrors {@link SchemaGenerator}'s byte-stability guarantee.
 */
public final class EventMappingIndexWriter {

    /** Classpath resource path of the index (relative to the build output root). */
    static final String INDEX_RESOURCE_PATH = "META-INF/event-mappings.idx";

    private EventMappingIndexWriter() {}

    /**
     * @param classesDir the module's compiled output root ({@code ${project.build.outputDirectory}})
     * @param metadata   validated mappings (FQCNs re-sorted defensively before writing)
     * @return the index path that was written
     */
    public static Path write(Path classesDir, List<EventMappingMetadata> metadata) throws IOException {
        Path indexPath = classesDir.resolve(INDEX_RESOURCE_PATH);
        Path metaInf = indexPath.getParent();
        Files.createDirectories(metaInf);

        String content = metadata.stream()
                .map(EventMappingMetadata::fqcn)
                .sorted()
                .collect(Collectors.joining("\n", "", "\n"));

        Path tmp = Files.createTempFile(metaInf, "event-mappings", ".idx.tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            moveIntoPlace(tmp, indexPath);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return indexPath;
    }

    private static void moveIntoPlace(Path tmp, Path indexPath) throws IOException {
        try {
            Files.move(tmp, indexPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Filesystems without atomic-move support still get a single-step replace.
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
