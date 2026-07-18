package com.example.contracts.orders.topology;

import com.example.amqp.topology.mapping.EventMappingIndexReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Packaged/build-output proof for issue 04: {@code process-classes} writes
 * {@code META-INF/event-mappings.idx} with the four order event FQCNs (sorted, trailing newline).
 * Index lives under build output only — never committed under {@code src/main/resources}.
 */
class OrderEventMappingIndexTest {

    @Test
    void buildOutputIndexListsTheFourOrderEventFqcnsSorted() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(EventMappingIndexReader.INDEX_RESOURCE_PATH)) {
            assertThat(in)
                    .as("expected %s on the order-contracts classpath after process-classes",
                            EventMappingIndexReader.INDEX_RESOURCE_PATH)
                    .isNotNull();

            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).endsWith("\n");
            assertThat(content.stripTrailing().lines().toList()).containsExactly(
                    "com.example.contracts.orders.OrderCancelled",
                    "com.example.contracts.orders.OrderCreated",
                    "com.example.contracts.orders.OrderFulfilled",
                    "com.example.contracts.orders.OrderShipped");
        }
    }
}
