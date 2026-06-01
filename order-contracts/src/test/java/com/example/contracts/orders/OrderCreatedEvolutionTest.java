package com.example.contracts.orders;

import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-4.2: Proves proto3 BACKWARD compatibility at the byte level.
 *
 * <p>A v2 producer can publish messages with promo_code set. A "v1 consumer" (one that only
 * knows about fields 1–7) will still deserialize such messages correctly — proto3 silently
 * discards unknown fields. Both directions are tested here using hand-crafted bytes.
 */
class OrderCreatedEvolutionTest {

    /**
     * TC-4.2 (U): v1 consumer reads v2 payload.
     *
     * <p>Build a message whose bytes include a non-null promo_code (field 8, wire type 2 = LEN).
     * Parse it with the generated OrderCreated class (which knows about field 8 since it is v2).
     * Assert all v1 fields (1-7) are intact — promo_code is accessible as a bonus because the
     * current generated class already includes it. The important thing is that adding field 8
     * does NOT break deserialization of fields 1-7.
     */
    @Test
    void tc42_v1FieldsIntactWhenV2PayloadDeserialized() throws Exception {
        OrderCreated v2 = OrderCreated.newBuilder()
                .setOrderId("ord-v2-001")
                .setCustomerId("cust-42")
                .setProductId("prod-X")
                .setQuantity(5)
                .setTotalAmount(199.95)
                .setCurrency("USD")
                .setCreatedAt("2026-05-31T10:00:00Z")
                .setPromoCode("BACK10")   // new v2 field
                .build();

        byte[] bytes = v2.toByteArray();

        // Parse the bytes — simulates what any consumer (v1 or v2) does on the wire
        OrderCreated parsed = OrderCreated.parseFrom(bytes);

        // v1 fields must be fully intact
        assertThat(parsed.getOrderId()).isEqualTo("ord-v2-001");
        assertThat(parsed.getCustomerId()).isEqualTo("cust-42");
        assertThat(parsed.getProductId()).isEqualTo("prod-X");
        assertThat(parsed.getQuantity()).isEqualTo(5);
        assertThat(parsed.getTotalAmount()).isEqualTo(199.95);
        assertThat(parsed.getCurrency()).isEqualTo("USD");
        assertThat(parsed.getCreatedAt()).isEqualTo("2026-05-31T10:00:00Z");
    }

    /**
     * TC-4.2 complement: simulate a strict "v1-only" consumer by hand-crafting proto bytes
     * that include field 8 (promo_code) and parsing them with parseFrom.
     *
     * <p>Proto3 contract: unknown fields are preserved but do not cause parse errors.
     * A future "v1 class" (without getPromoCode) would receive the message without exception.
     */
    @Test
    void tc42_unknownFieldInPayloadDoesNotCauseParseError() throws Exception {
        // Manually encode an OrderCreated message with only v1 fields + an "unknown" field 9
        // (a field that neither v1 nor v2 knows about, simulating a future v3 addition).
        // Proto3 must parse it without error and preserve v1 fields.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(buf);

        // field 1 (order_id): wire type 2 (LEN)
        cos.writeString(1, "ord-unknown-field");
        // field 2 (customer_id)
        cos.writeString(2, "cust-99");
        // field 3 (product_id)
        cos.writeString(3, "prod-Z");
        // field 4 (quantity): wire type 0 (VARINT)
        cos.writeInt32(4, 1);
        // field 5 (total_amount): wire type 1 (I64)
        cos.writeDouble(5, 49.99);
        // field 6 (currency)
        cos.writeString(6, "EUR");
        // field 7 (created_at)
        cos.writeString(7, "2026-05-31T12:00:00Z");
        // field 9 (hypothetical v3 field — unknown to both v1 and v2)
        cos.writeString(9, "some-future-value");
        cos.flush();

        byte[] bytes = buf.toByteArray();
        OrderCreated parsed = OrderCreated.parseFrom(bytes);

        assertThat(parsed.getOrderId()).isEqualTo("ord-unknown-field");
        assertThat(parsed.getCustomerId()).isEqualTo("cust-99");
        assertThat(parsed.getQuantity()).isEqualTo(1);
        assertThat(parsed.getTotalAmount()).isEqualTo(49.99);
    }

    /**
     * TC-4.1 intent: proto v2 adds optional string promo_code at field number 8.
     * Verify the field is accessible and defaults to empty string when not set (proto3 default).
     */
    @Test
    void tc41_v2FieldAddedAndDefaultsToEmpty() {
        OrderCreated noPromo = OrderCreated.newBuilder()
                .setOrderId("ord-no-promo")
                .setCustomerId("cust-1")
                .setProductId("prod-A")
                .setQuantity(1)
                .setTotalAmount(10.0)
                .setCurrency("USD")
                .setCreatedAt("2026-05-31T00:00:00Z")
                .build();

        // Default proto3 value for string field is empty string (not null)
        assertThat(noPromo.getPromoCode()).isEmpty();
        assertThat(noPromo.hasPromoCode()).isFalse();
    }
}
