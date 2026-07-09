package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when an order is cancelled. Code-first source of truth: this annotated record is the
 * contract, and {@code order-cancelled.schema.json} is generated from it (never hand-edited).
 */
@JsonClassDescription("Emitted when an order is cancelled.")
public record OrderCancelled(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the cancelled order.")
        UUID orderId,

        @NotNull
        @JsonPropertyDescription("Human-readable reason the order was cancelled.")
        String reason,

        @DecimalMin("0.00")
        @JsonPropertyDescription("Amount refunded to the customer, if any; must be non-negative.")
        BigDecimal refundAmount,

        @JsonPropertyDescription("Instant the order was cancelled (ISO-8601).")
        Instant cancelledAt
) {}
