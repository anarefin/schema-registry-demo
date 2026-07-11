package com.example.contracts.orders;

import com.example.amqp.topology.mapping.GenerateSchema;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when an order is handed to a carrier. Code-first source of truth: this annotated record
 * is the contract, and {@code order-shipped.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@JsonClassDescription("Emitted when an order is shipped to the customer.")
public record OrderShipped(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the shipped order.")
        UUID orderId,

        @NotNull
        @Size(min = 1, max = 64)
        @JsonPropertyDescription("Carrier tracking number for the shipment.")
        String trackingNumber,

        @NotNull
        @Pattern(regexp = "^(DHL|FEDEX|UPS)$")
        @JsonPropertyDescription("Shipping carrier; one of DHL, FEDEX, UPS.")
        String carrier,

        @JsonPropertyDescription("Instant the order was shipped (ISO-8601).")
        Instant shippedAt
) {}
