package com.example.contracts.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TC-3.1 (U): generated OrderCreated POJO round-trips to/from raw JSON bytes.
 * Spec §6: NO envelope — the wire format is the raw JSON document only.
 */
class OrderCreatedTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tc31_jsonRoundTrip() throws Exception {
        OrderCreated original = new OrderCreated()
                .withOrderId("ord-001")
                .withCustomerId("cust-abc")
                .withProductId("prod-xyz")
                .withQuantity(2)
                .withTotalAmount(49.99)
                .withCurrency("USD")
                .withCreatedAt("2026-05-31T00:00:00Z");

        byte[] bytes = objectMapper.writeValueAsBytes(original); // raw JSON — no envelope

        assertThat(bytes).isNotEmpty();

        OrderCreated parsed = objectMapper.readValue(bytes, OrderCreated.class);

        assertThat(parsed.getOrderId()).isEqualTo("ord-001");
        assertThat(parsed.getCustomerId()).isEqualTo("cust-abc");
        assertThat(parsed.getProductId()).isEqualTo("prod-xyz");
        assertThat(parsed.getQuantity()).isEqualTo(2);
        assertThat(parsed.getTotalAmount()).isEqualTo(49.99);
        assertThat(parsed.getCurrency()).isEqualTo("USD");
        assertThat(parsed.getCreatedAt()).isEqualTo("2026-05-31T00:00:00Z");
    }

    @Test
    void deserializesValidJson() throws Exception {
        String json = """
                {
                  "orderId": "ord-002",
                  "customerId": "cust-def",
                  "productId": "prod-uvw",
                  "quantity": 1,
                  "totalAmount": 10.50,
                  "currency": "EUR",
                  "createdAt": "2026-06-01T12:00:00Z"
                }
                """;

        OrderCreated parsed = objectMapper.readValue(json, OrderCreated.class);

        assertThat(parsed.getOrderId()).isEqualTo("ord-002");
        assertThat(parsed.getQuantity()).isEqualTo(1);
        assertThat(parsed.getTotalAmount()).isEqualTo(10.50);
        assertThat(parsed.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void toleratesExtraFieldsDuringDeserialization() {
        // Forward-compat tolerance: Jackson should ignore unknown fields.
        String jsonWithExtraField = """
                {
                  "orderId": "ord-003",
                  "customerId": "cust-ghi",
                  "productId": "prod-rst",
                  "quantity": 3,
                  "totalAmount": 99.00,
                  "currency": "USD",
                  "someFutureField": "ignored"
                }
                """;

        assertThatCode(() -> objectMapper.readValue(jsonWithExtraField, OrderCreated.class))
                .doesNotThrowAnyException();
    }
}
