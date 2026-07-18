package com.example.contracts.orders;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.orders.topology.OrderEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when an order is fulfilled. Code-first source of truth: this annotated record is the
 * contract, and {@code order-fulfilled.schema.json} is generated from it. Carries three nested
 * value objects ({@link OrderBuyer}, {@link ShippingAddress}, {@link PaymentDetails}), each
 * inlined into the generated schema.
 */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.FULFILLED_ROUTING_KEY)
@JsonClassDescription("Emitted when an order is fulfilled and ready for delivery.")
public record OrderFulfilled(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the order.")
        UUID orderId,

        @NotNull
        @Valid
        @JsonPropertyDescription("The customer who placed the order.")
        OrderBuyer buyer,

        @NotNull
        @Valid
        @JsonPropertyDescription("The shipping destination for the order.")
        ShippingAddress shipping,

        @NotNull
        @Valid
        @JsonPropertyDescription("Payment details for the fulfilled order.")
        PaymentDetails payment,

        @JsonPropertyDescription("Instant the order was fulfilled (ISO-8601).")
        Instant fulfilledAt
) {}
