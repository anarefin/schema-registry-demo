package com.example.contracts.orders;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.orders.topology.OrderEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted when an order is handed to a carrier. Code-first source of truth: this annotated class
 * is the contract, and {@code order-shipped.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.SHIPPED_ROUTING_KEY)
@JsonClassDescription("Emitted when an order is shipped to the customer.")
public final class OrderShipped {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the shipped order.")
    private final UUID orderId;

    @NotNull
    @Size(min = 1, max = 64)
    @JsonPropertyDescription("Carrier tracking number for the shipment.")
    private final String trackingNumber;

    @NotNull
    @Pattern(regexp = "^(DHL|FEDEX|UPS)$")
    @JsonPropertyDescription("Shipping carrier; one of DHL, FEDEX, UPS.")
    private final String carrier;

    @JsonPropertyDescription("Instant the order was shipped (ISO-8601).")
    private final Instant shippedAt;

    @JsonCreator
    public OrderShipped(
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("trackingNumber") String trackingNumber,
            @JsonProperty("carrier") String carrier,
            @JsonProperty("shippedAt") Instant shippedAt) {
        this.orderId = orderId;
        this.trackingNumber = trackingNumber;
        this.carrier = carrier;
        this.shippedAt = shippedAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getCarrier() {
        return carrier;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderShipped that)) {
            return false;
        }
        return Objects.equals(orderId, that.orderId)
                && Objects.equals(trackingNumber, that.trackingNumber)
                && Objects.equals(carrier, that.carrier)
                && Objects.equals(shippedAt, that.shippedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, trackingNumber, carrier, shippedAt);
    }

    @Override
    public String toString() {
        return "OrderShipped["
                + "orderId=" + orderId
                + ", trackingNumber=" + trackingNumber
                + ", carrier=" + carrier
                + ", shippedAt=" + shippedAt
                + ']';
    }
}
