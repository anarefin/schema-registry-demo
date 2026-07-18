package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.EventMappingIndexReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Packaged/build-output proof for issue 05: {@code process-classes} writes
 * {@code META-INF/event-mappings.idx} with the three customer event FQCNs (sorted, trailing newline).
 * Index lives under build output only — never committed under {@code src/main/resources}.
 */
class CustomerEventMappingIndexTest {

    @Test
    void buildOutputIndexListsTheThreeCustomerEventFqcnsSorted() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(EventMappingIndexReader.INDEX_RESOURCE_PATH)) {
            assertThat(in)
                    .as("expected %s on the customer-contracts classpath after process-classes",
                            EventMappingIndexReader.INDEX_RESOURCE_PATH)
                    .isNotNull();

            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).endsWith("\n");
            assertThat(content.stripTrailing().lines().toList()).containsExactly(
                    "com.example.contracts.customers.CustomerAddressAdded",
                    "com.example.contracts.customers.CustomerRegistered",
                    "com.example.contracts.customers.CustomerTierChanged");
        }
    }
}
