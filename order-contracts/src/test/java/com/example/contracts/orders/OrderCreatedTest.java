package com.example.contracts.orders;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-3.1 (U): Generated OrderCreated round-trips to/from pure protobuf bytes.
 * Spec §6: NO magic byte, NO length prefix — raw protobuf binary only.
 */
class OrderCreatedTest {

    @Test
    void tc31_protoRoundTrip() throws InvalidProtocolBufferException {
        OrderCreated original = OrderCreated.newBuilder()
                .setOrderId("ord-001")
                .setCustomerId("cust-abc")
                .setProductId("prod-xyz")
                .setQuantity(2)
                .setTotalAmount(49.99)
                .setCurrency("USD")
                .setCreatedAt("2026-05-31T00:00:00Z")
                .build();

        byte[] bytes = original.toByteArray(); // pure protobuf — no magic byte, no length prefix

        assertThat(bytes).isNotEmpty();

        OrderCreated parsed = OrderCreated.parseFrom(bytes);

        assertThat(parsed.getOrderId()).isEqualTo("ord-001");
        assertThat(parsed.getCustomerId()).isEqualTo("cust-abc");
        assertThat(parsed.getProductId()).isEqualTo("prod-xyz");
        assertThat(parsed.getQuantity()).isEqualTo(2);
        assertThat(parsed.getTotalAmount()).isEqualTo(49.99);
        assertThat(parsed.getCurrency()).isEqualTo("USD");
        assertThat(parsed.getCreatedAt()).isEqualTo("2026-05-31T00:00:00Z");
    }
}
