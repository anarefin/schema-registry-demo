package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Shipping destination embedded in {@link OrderFulfilled}; its schema is inlined into the owning
 * event's generated schema.
 */
@JsonClassDescription("The shipping destination for the order.")
public record ShippingAddress(

        @NotNull
        @JsonPropertyDescription("First line of the street address.")
        String line1,

        @JsonPropertyDescription("Optional second line of the street address.")
        String line2,

        @NotNull
        @JsonPropertyDescription("City or locality.")
        String city,

        @NotNull
        @JsonPropertyDescription("Postal or ZIP code.")
        String postalCode,

        @NotNull
        @Size(min = 2, max = 2)
        @JsonPropertyDescription("ISO-3166-1 alpha-2 country code, e.g. US.")
        String countryCode
) {}
