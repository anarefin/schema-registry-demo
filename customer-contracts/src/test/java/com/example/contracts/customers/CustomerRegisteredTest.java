package com.example.contracts.customers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TC-2.1 (U): generated CustomerRegistered POJO deserializes a valid sample JSON.
 * Also verifies Jackson tolerance for unknown fields (TC-2.5 precondition).
 */
class CustomerRegisteredTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesValidJson() throws Exception {
        String json = """
                {
                  "customerId": "c1a2b3c4-0000-0000-0000-000000000001",
                  "email": "alice@example.com",
                  "firstName": "Alice",
                  "lastName": "Smith",
                  "registeredAt": "2026-05-31T04:00:00Z"
                }
                """;

        CustomerRegistered cr = objectMapper.readValue(json, CustomerRegistered.class);

        assertThat(cr.getCustomerId()).isEqualTo("c1a2b3c4-0000-0000-0000-000000000001");
        assertThat(cr.getEmail()).isEqualTo("alice@example.com");
        assertThat(cr.getFirstName()).isEqualTo("Alice");
        assertThat(cr.getLastName()).isEqualTo("Smith");
        assertThat(cr.getRegisteredAt()).isEqualTo("2026-05-31T04:00:00Z");
    }

    @Test
    void roundTripsViaJackson() throws Exception {
        CustomerRegistered original = new CustomerRegistered();
        original.setCustomerId("test-uuid");
        original.setEmail("bob@example.com");
        original.setFirstName("Bob");
        original.setLastName("Jones");

        String json = objectMapper.writeValueAsString(original);
        CustomerRegistered roundTripped = objectMapper.readValue(json, CustomerRegistered.class);

        assertThat(roundTripped.getCustomerId()).isEqualTo("test-uuid");
        assertThat(roundTripped.getEmail()).isEqualTo("bob@example.com");
        assertThat(roundTripped.getFirstName()).isEqualTo("Bob");
        assertThat(roundTripped.getLastName()).isEqualTo("Jones");
    }

    @Test
    void toleratesExtraFieldsDuringDeserialization() {
        // TC-2.5 precondition: Jackson should ignore unknown fields (backward-compat tolerance).
        String jsonWithExtraField = """
                {
                  "customerId": "x",
                  "email": "x@x.com",
                  "firstName": "X",
                  "lastName": "Y",
                  "promoCode": "SUMMER2026"
                }
                """;

        assertThatCode(() -> objectMapper.readValue(jsonWithExtraField, CustomerRegistered.class))
                .doesNotThrowAnyException();
    }
}
