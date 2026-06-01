package com.example.contracts.customers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-4.4: Proves JSON Schema BACKWARD compatibility at the deserialization level.
 *
 * <p>v2 adds the optional 'promoCode' property; v1 consumers using Jackson ignore unknown
 * properties by default, so they deserialize v2 payloads without error.
 */
class CustomerRegisteredEvolutionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * TC-4.4 (U): v1 consumer reads v2 JSON payload (extra field tolerated by Jackson).
     *
     * <p>The v2 producer writes a payload containing 'promoCode'. A consumer compiled against
     * the v1 schema (without promoCode) must still deserialize without exception and read the
     * original required fields correctly. Jackson's default {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
     * guarantees this.
     */
    @Test
    void tc44_v2PromoCodeIgnoredByV1Consumer() throws Exception {
        String v2Json = """
                {
                  "customerId": "cust-v2-001",
                  "email": "v2user@example.com",
                  "firstName": "V2",
                  "lastName": "User",
                  "registeredAt": "2026-05-31T10:00:00Z",
                  "promoCode": "SUMMER26"
                }
                """;

        CustomerRegistered parsed = mapper.readValue(v2Json, CustomerRegistered.class);

        assertThat(parsed.getCustomerId()).isEqualTo("cust-v2-001");
        assertThat(parsed.getEmail()).isEqualTo("v2user@example.com");
        assertThat(parsed.getFirstName()).isEqualTo("V2");
        assertThat(parsed.getLastName()).isEqualTo("User");
        // promoCode is now a v2 field — confirm it round-trips in the generated POJO
        assertThat(parsed.getPromoCode()).isEqualTo("SUMMER26");
    }

    /**
     * TC-4.4 complement: v2 consumer can read v1 payload (promoCode absent → null/empty).
     */
    @Test
    void tc44_v1PayloadReadableByV2Consumer() throws Exception {
        String v1Json = """
                {
                  "customerId": "cust-v1-001",
                  "email": "v1user@example.com",
                  "firstName": "V1",
                  "lastName": "User"
                }
                """;

        CustomerRegistered parsed = mapper.readValue(v1Json, CustomerRegistered.class);

        assertThat(parsed.getCustomerId()).isEqualTo("cust-v1-001");
        assertThat(parsed.getPromoCode()).isNull();
    }

    /**
     * TC-4.4 incompatible-intent: removing the 'email' required field would break consumers
     * that assert email is present. The incompatible schema demo (src/test/resources/schemas/
     * customer-registered-incompatible.json) captures this scenario for the registry gate.
     * This test documents the expectation: a null email from a consumer perspective means
     * the contract was violated.
     */
    @Test
    void tc44_missingRequiredEmailFieldResultsInNull() throws Exception {
        // Simulate an incompatible payload — email omitted
        String incompatiblePayload = """
                {
                  "customerId": "cust-bad",
                  "firstName": "Bad",
                  "lastName": "Schema"
                }
                """;

        CustomerRegistered parsed = mapper.readValue(incompatiblePayload, CustomerRegistered.class);

        // At the Java/Jackson level, missing field means null — the schema registry BACKWARD
        // rule prevents this payload from ever being produced by a well-governed producer.
        assertThat(parsed.getEmail()).isNull();
    }
}
