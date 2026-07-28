package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Buyer identity embedded in {@link OrderFulfilled}; its schema is inlined into the owning event's
 * generated schema.
 */
@JsonClassDescription("The customer who placed the order.")
public final class OrderBuyer {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the customer.")
    private final UUID customerId;

    @NotNull
    @Email
    @JsonPropertyDescription("Customer email address.")
    private final String email;

    @NotNull
    @JsonPropertyDescription("Display name shown on the order.")
    private final String displayName;

    @JsonCreator
    public OrderBuyer(
            @JsonProperty("customerId") UUID customerId,
            @JsonProperty("email") String email,
            @JsonProperty("displayName") String displayName) {
        this.customerId = customerId;
        this.email = email;
        this.displayName = displayName;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderBuyer that)) {
            return false;
        }
        return Objects.equals(customerId, that.customerId)
                && Objects.equals(email, that.email)
                && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, email, displayName);
    }

    @Override
    public String toString() {
        return "OrderBuyer["
                + "customerId=" + customerId
                + ", email=" + email
                + ", displayName=" + displayName
                + ']';
    }
}
