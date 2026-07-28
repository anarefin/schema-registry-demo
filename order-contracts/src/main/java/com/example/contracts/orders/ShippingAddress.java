package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * Shipping destination embedded in {@link OrderFulfilled}; its schema is inlined into the owning
 * event's generated schema.
 */
@JsonClassDescription("The shipping destination for the order.")
public final class ShippingAddress {

    @NotNull
    @JsonPropertyDescription("First line of the street address.")
    private final String line1;

    @JsonPropertyDescription("Optional second line of the street address.")
    private final String line2;

    @NotNull
    @JsonPropertyDescription("City or locality.")
    private final String city;

    @NotNull
    @JsonPropertyDescription("Postal or ZIP code.")
    private final String postalCode;

    @NotNull
    @Size(min = 2, max = 2)
    @JsonPropertyDescription("ISO-3166-1 alpha-2 country code, e.g. US.")
    private final String countryCode;

    @JsonCreator
    public ShippingAddress(
            @JsonProperty("line1") String line1,
            @JsonProperty("line2") String line2,
            @JsonProperty("city") String city,
            @JsonProperty("postalCode") String postalCode,
            @JsonProperty("countryCode") String countryCode) {
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountryCode() {
        return countryCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShippingAddress that)) {
            return false;
        }
        return Objects.equals(line1, that.line1)
                && Objects.equals(line2, that.line2)
                && Objects.equals(city, that.city)
                && Objects.equals(postalCode, that.postalCode)
                && Objects.equals(countryCode, that.countryCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(line1, line2, city, postalCode, countryCode);
    }

    @Override
    public String toString() {
        return "ShippingAddress["
                + "line1=" + line1
                + ", line2=" + line2
                + ", city=" + city
                + ", postalCode=" + postalCode
                + ", countryCode=" + countryCode
                + ']';
    }
}
