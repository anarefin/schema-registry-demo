package com.example.contracts.orders;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.orders.topology.OrderEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a new order is placed. Code-first source of truth: this annotated record is the
 * contract, and {@code order-created.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.CREATED_ROUTING_KEY)
@JsonClassDescription("Emitted when a customer places a new order.")
public record OrderCreated(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the order.")
        UUID orderId,

        @NotNull
        @JsonPropertyDescription("Identifier of the customer who placed the order.")
        UUID customerId,

        @NotNull
        @JsonPropertyDescription("Identifier of the ordered product.")
        UUID productId,

        @NotNull
        @Min(1)
        @JsonPropertyDescription("Number of units ordered; must be at least 1.")
        Integer quantity,

        @NotNull
        @DecimalMin(value = "0.01", inclusive = false)
        @JsonPropertyDescription("Order total in the given currency; must be greater than 0.01.")
        BigDecimal totalAmount,

        @NotNull
        @Size(min = 3, max = 3)
        @JsonPropertyDescription("ISO-4217 three-letter currency code, e.g. USD.")
        String currency,

        @JsonPropertyDescription("Instant the order was created (ISO-8601).")
        Instant createdAt
) {}
