package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payment information embedded in {@link OrderFulfilled}; its schema is inlined into the owning
 * event's generated schema.
 */
@JsonClassDescription("Payment details for the fulfilled order.")
public record PaymentDetails(

        @NotNull
        @JsonPropertyDescription("Payment method used, e.g. CARD or PAYPAL.")
        String method,

        @NotNull
        @DecimalMin(value = "0.01", inclusive = false)
        @JsonPropertyDescription("Amount charged; must be greater than 0.01.")
        BigDecimal amount,

        @NotNull
        @Size(min = 3, max = 3)
        @JsonPropertyDescription("ISO-4217 three-letter currency code, e.g. USD.")
        String currency
) {}
