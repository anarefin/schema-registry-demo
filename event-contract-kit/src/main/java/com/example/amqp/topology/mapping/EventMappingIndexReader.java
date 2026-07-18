package com.example.amqp.topology.mapping;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;

/**
 * Reads and merges every {@code META-INF/event-mappings.idx} resource visible to a class loader —
 * the build-time index emitted by {@code schema-gen-tools}
 * ({@code EventMappingIndexWriter}: one FQCN per line, UTF-8, lexically sorted, trailing newline).
 * Across a multi-jar classpath each contracts jar contributes its own index; this merges them into
 * one de-duplicated, lexically sorted list of fully-qualified class names.
 *
 * <p>Pure index I/O only — <em>no classpath package scanning</em>. The set of event types is
 * exactly the union of the committed index lines; classes are loaded later (by
 * {@link IndexedEventMappings}) strictly from these names. A malformed line (blank or containing
 * whitespace) fails fast via {@link EventMappingRegistrationException} so a corrupt index never
 * registers partial or wrong mappings. A trailing blank line (from the trailing newline) is
 * tolerated; interior blank lines are not.
 */
public final class EventMappingIndexReader {

    /** Classpath resource path of the index (relative to each root/jar), shared with the writer. */
    public static final String INDEX_RESOURCE_PATH = "META-INF/event-mappings.idx";

    private EventMappingIndexReader() {}

    /**
     * @param classLoader the loader whose classpath is searched for index resources
     * @return merged, de-duplicated, lexically sorted FQCNs (empty when no index resource exists)
     */
    public static List<String> readAll(ClassLoader classLoader) {
        Enumeration<URL> resources;
        try {
            resources = classLoader.getResources(INDEX_RESOURCE_PATH);
        } catch (IOException e) {
            throw new EventMappingRegistrationException(
                    "Failed to enumerate " + INDEX_RESOURCE_PATH + " resources", e);
        }

        // TreeSet: FQCN de-duplication (same class listed in two jars) + deterministic sort.
        TreeSet<String> fqcns = new TreeSet<>();
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            fqcns.addAll(parse(url));
        }
        return List.copyOf(fqcns);
    }

    private static List<String> parse(URL url) {
        String content;
        try (var in = url.openStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EventMappingRegistrationException("Failed to read index " + url, e);
        }

        // A wholly-empty resource (0 bytes) is an empty index, not a malformed line — treat it the
        // same as the writer's empty-index form ("\n"). (String.split on "" returns [""], which the
        // loop below would otherwise reject.)
        if (content.isEmpty()) {
            return List.of();
        }

        List<String> fqcns = new ArrayList<>();
        // Default split drops trailing empties, so a valid trailing newline (and an "" from an
        // empty index) yields no phantom line; interior blanks survive and are rejected below.
        for (String raw : content.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty() || containsWhitespace(line)) {
                throw new EventMappingRegistrationException(
                        "Malformed line \"" + line + "\" in event index " + url
                                + " (expected one FQCN per line)");
            }
            fqcns.add(line);
        }
        return fqcns;
    }

    private static boolean containsWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
