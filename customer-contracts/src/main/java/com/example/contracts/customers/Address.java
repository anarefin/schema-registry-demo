package com.example.contracts.customers;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A postal address. Nested value object embedded in {@link CustomerAddressAdded}; its schema is
 * inlined into the owning event's generated schema.
 */
@JsonClassDescription("A customer postal address.")
public record Address(

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

        @Size(min = 2, max = 2)
        @JsonPropertyDescription("ISO-3166-1 alpha-2 country code, e.g. US.")
        String countryCode
) {}
