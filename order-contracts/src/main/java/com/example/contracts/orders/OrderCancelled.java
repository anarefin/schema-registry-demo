package com.example.contracts.orders;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.orders.topology.OrderEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted when an order is cancelled. Code-first source of truth: this annotated class is the
 * contract, and {@code order-cancelled.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.CANCELLED_ROUTING_KEY)
@JsonClassDescription("Emitted when an order is cancelled.")
public final class OrderCancelled {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the cancelled order.")
    private final UUID orderId;

    @NotNull
    @JsonPropertyDescription("Human-readable reason the order was cancelled.")
    private final String reason;

    @DecimalMin("0.00")
    @JsonPropertyDescription("Amount refunded to the customer, if any; must be non-negative.")
    private final BigDecimal refundAmount;

    @JsonPropertyDescription("Instant the order was cancelled (ISO-8601).")
    private final Instant cancelledAt;

    @JsonCreator
    public OrderCancelled(
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("reason") String reason,
            @JsonProperty("refundAmount") BigDecimal refundAmount,
            @JsonProperty("cancelledAt") Instant cancelledAt) {
        this.orderId = orderId;
        this.reason = reason;
        this.refundAmount = refundAmount;
        this.cancelledAt = cancelledAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderCancelled that)) {
            return false;
        }
        return Objects.equals(orderId, that.orderId)
                && Objects.equals(reason, that.reason)
                && Objects.equals(refundAmount, that.refundAmount)
                && Objects.equals(cancelledAt, that.cancelledAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, reason, refundAmount, cancelledAt);
    }

    @Override
    public String toString() {
        return "OrderCancelled["
                + "orderId=" + orderId
                + ", reason=" + reason
                + ", refundAmount=" + refundAmount
                + ", cancelledAt=" + cancelledAt
                + ']';
    }
}
