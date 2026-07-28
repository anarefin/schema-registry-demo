package com.example.contracts.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Value-object tests for the code-first order events. The class IS the contract; the wire format
 * is the raw JSON document only (spec §6 — no envelope). These verify Jackson round-trips the
 * class shape faithfully (field names, types, optional fields) so producer/consumer agree.
 */
class OrderEventsTest {

    // findAndAddModules() picks up jackson-datatype-jsr310 (test scope) so Instant renders as
    // ISO-8601 — the same shape the runtime converter emits.
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void orderCreatedRoundTrips() throws Exception {
        OrderCreated original = new OrderCreated(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                new BigDecimal("49.99"),
                "USD",
                Instant.parse("2026-05-31T00:00:00Z"));

        byte[] bytes = mapper.writeValueAsBytes(original); // raw JSON — no envelope
        OrderCreated parsed = mapper.readValue(bytes, OrderCreated.class);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void orderCreatedFieldNamesMatchProperties() throws Exception {
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, new BigDecimal("10.50"), "EUR", Instant.parse("2026-06-01T12:00:00Z"));

        var json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "orderId", "customerId", "productId", "quantity", "totalAmount", "currency", "createdAt");
        assertThat(json.get("currency").asText()).isEqualTo("EUR");
        assertThat(json.get("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void orderShippedRoundTrips() throws Exception {
        OrderShipped original = new OrderShipped(
                UUID.randomUUID(), "1Z999AA10123456784", "UPS", Instant.parse("2026-06-02T08:15:00Z"));

        OrderShipped parsed = mapper.readValue(mapper.writeValueAsBytes(original), OrderShipped.class);

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getCarrier()).isEqualTo("UPS");
    }

    @Test
    void orderCancelledRoundTripsWithOptionalRefund() throws Exception {
        OrderCancelled withRefund = new OrderCancelled(
                UUID.randomUUID(), "Customer request", new BigDecimal("12.00"),
                Instant.parse("2026-06-03T09:00:00Z"));
        OrderCancelled withoutRefund = new OrderCancelled(
                UUID.randomUUID(), "Out of stock", null, Instant.parse("2026-06-03T09:00:00Z"));

        assertThat(mapper.readValue(mapper.writeValueAsBytes(withRefund), OrderCancelled.class))
                .isEqualTo(withRefund);

        OrderCancelled parsedNoRefund =
                mapper.readValue(mapper.writeValueAsBytes(withoutRefund), OrderCancelled.class);
        assertThat(parsedNoRefund).isEqualTo(withoutRefund);
        assertThat(parsedNoRefund.getRefundAmount()).isNull();
    }

    @Test
    void orderFulfilledRoundTripsNestedObjects() throws Exception {
        OrderBuyer buyer = new OrderBuyer(
                UUID.randomUUID(), "buyer@example.com", "Jane Doe");
        ShippingAddress shipping = new ShippingAddress(
                "221B Baker Street", null, "London", "NW1 6XE", "GB");
        PaymentDetails payment = new PaymentDetails(
                "CARD", new BigDecimal("149.99"), "GBP");
        OrderFulfilled original = new OrderFulfilled(
                UUID.randomUUID(), buyer, shipping, payment,
                Instant.parse("2026-06-04T10:00:00Z"));

        var json = mapper.readTree(mapper.writeValueAsBytes(original));
        assertThat(json.get("buyer").isObject()).isTrue();
        assertThat(json.get("buyer").get("email").asText()).isEqualTo("buyer@example.com");
        assertThat(json.get("shipping").isObject()).isTrue();
        assertThat(json.get("shipping").get("city").asText()).isEqualTo("London");
        assertThat(json.get("payment").isObject()).isTrue();
        assertThat(json.get("payment").get("method").asText()).isEqualTo("CARD");
        assertThat(json.get("payment").get("currency").asText()).isEqualTo("GBP");

        OrderFulfilled parsed =
                mapper.readValue(mapper.writeValueAsBytes(original), OrderFulfilled.class);
        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getShipping().getLine2()).isNull();
    }

    @Test
    void deserializesFromHandWrittenJson() throws Exception {
        String json = """
                {
                  "orderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "customerId": "3fa85f64-5717-4562-b3fc-2c963f66afa7",
                  "productId": "3fa85f64-5717-4562-b3fc-2c963f66afa8",
                  "quantity": 3,
                  "totalAmount": 99.90,
                  "currency": "GBP",
                  "createdAt": "2026-06-04T10:00:00Z"
                }
                """;

        OrderCreated parsed = mapper.readValue(json, OrderCreated.class);

        assertThat(parsed.getOrderId()).isEqualTo(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
        assertThat(parsed.getTotalAmount()).isEqualByComparingTo("99.90");
        assertThat(parsed.getCurrency()).isEqualTo("GBP");
    }
}
