package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Buyer identity embedded in {@link OrderFulfilled}; its schema is inlined into the owning event's
 * generated schema.
 */
@JsonClassDescription("The customer who placed the order.")
public record OrderBuyer(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the customer.")
        UUID customerId,

        @NotNull
        @Email
        @JsonPropertyDescription("Customer email address.")
        String email,

        @NotNull
        @JsonPropertyDescription("Display name shown on the order.")
        String displayName
) {}
