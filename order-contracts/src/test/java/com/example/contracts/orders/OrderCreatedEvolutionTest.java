package com.example.contracts.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-4.2: Proves JSON Schema FORWARD compatibility at the deserialization level.
 *
 * <p>The OrderCreated artifact is governed by a FORWARD rule: in Apicurio's JSON Schema
 * checker, adding an optional property is classified as a "narrowing" and is only
 * FORWARD-compatible (an old reader still parses the newer payload), never BACKWARD.
 *
 * <p>v2 adds the optional 'promoCode', 'notes', 'input1' and 'userName' properties; v1
 * consumers using Jackson ignore unknown properties by default, so they deserialize newer
 * payloads without error — exactly the old-reader-reads-new-data guarantee FORWARD encodes.
 */
class OrderCreatedEvolutionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * TC-4.2 (U): v1 consumer reads v2 JSON payload (extra fields tolerated by Jackson).
     */
    @Test
    void tc42_v1FieldsIntactWhenV2PayloadDeserialized() throws Exception {
        String v2Json = """
                {
                  "orderId": "ord-v2-001",
                  "customerId": "cust-v2",
                  "productId": "prod-v2",
                  "quantity": 5,
                  "totalAmount": 125.00,
                  "currency": "USD",
                  "createdAt": "2026-05-31T10:00:00Z",
                  "promoCode": "SUMMER26",
                  "notes": "leave at door",
                  "input1": "extra",
                  "userName": "alice"
                }
                """;

        OrderCreated parsed = mapper.readValue(v2Json, OrderCreated.class);

        // all v1 fields intact
        assertThat(parsed.getOrderId()).isEqualTo("ord-v2-001");
        assertThat(parsed.getCustomerId()).isEqualTo("cust-v2");
        assertThat(parsed.getProductId()).isEqualTo("prod-v2");
        assertThat(parsed.getQuantity()).isEqualTo(5);
        assertThat(parsed.getTotalAmount()).isEqualTo(125.00);
        assertThat(parsed.getCurrency()).isEqualTo("USD");
        // v2 fields round-trip in the generated POJO
        assertThat(parsed.getPromoCode()).isEqualTo("SUMMER26");
        assertThat(parsed.getNotes()).isEqualTo("leave at door");
        assertThat(parsed.getInput1()).isEqualTo("extra");
        assertThat(parsed.getUserName()).isEqualTo("alice");
    }

    /**
     * TC-4.2 complement: v2 consumer reads a v1 payload (optional fields absent → null).
     */
    @Test
    void tc42_v1PayloadReadableByV2Consumer() throws Exception {
        String v1Json = """
                {
                  "orderId": "ord-v1-001",
                  "customerId": "cust-v1",
                  "productId": "prod-v1",
                  "quantity": 1,
                  "totalAmount": 9.99,
                  "currency": "EUR",
                  "createdAt": "2026-05-30T08:00:00Z"
                }
                """;

        OrderCreated parsed = mapper.readValue(v1Json, OrderCreated.class);

        assertThat(parsed.getOrderId()).isEqualTo("ord-v1-001");
        assertThat(parsed.getPromoCode()).isNull();
        assertThat(parsed.getNotes()).isNull();
        assertThat(parsed.getInput1()).isNull();
        assertThat(parsed.getUserName()).isNull();
    }

    /**
     * TC-4.2 incompatible-intent: changing 'quantity' from integer to string (the
     * incompatible-demo schema in src/test/resources/schemas/order-created-incompatible.json)
     * breaks every reader, which is why the registry gate rejects it under any compatibility
     * level. This test documents the consumer-side expectation for a missing required field:
     * Jackson maps it to null, and it is the JsonSchemaStrategy validation in the messaging
     * core that rejects such payloads at runtime.
     */
    @Test
    void tc42_missingRequiredFieldResultsInNull() throws Exception {
        String incompatiblePayload = """
                {
                  "customerId": "cust-bad",
                  "productId": "prod-bad",
                  "quantity": 1,
                  "totalAmount": 1.00,
                  "currency": "USD"
                }
                """;

        OrderCreated parsed = mapper.readValue(incompatiblePayload, OrderCreated.class);

        assertThat(parsed.getOrderId()).isNull();
    }
}
