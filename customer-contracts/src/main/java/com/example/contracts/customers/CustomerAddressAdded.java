package com.example.contracts.customers;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.customers.topology.CustomerEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted when an address is added to a customer. Code-first source of truth: this annotated class
 * is the contract, and {@code customer-address-added.schema.json} is generated from it. Carries a
 * nested {@link Address} value object, inlined into the generated schema.
 */
@GenerateSchema
@EventMapping(
        groupId = "events.customers",
        exchange = CustomerEventRouting.EXCHANGE,
        routingKey = CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY)
@JsonClassDescription("Emitted when a customer adds a postal address.")
public final class CustomerAddressAdded {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the customer.")
    private final UUID customerId;

    @NotNull
    @Valid
    @JsonPropertyDescription("The address that was added.")
    private final Address address;

    @JsonPropertyDescription("Instant the address was added (ISO-8601).")
    private final Instant addedAt;

    @JsonCreator
    public CustomerAddressAdded(
            @JsonProperty("customerId") UUID customerId,
            @JsonProperty("address") Address address,
            @JsonProperty("addedAt") Instant addedAt) {
        this.customerId = customerId;
        this.address = address;
        this.addedAt = addedAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Address getAddress() {
        return address;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CustomerAddressAdded that)) {
            return false;
        }
        return Objects.equals(customerId, that.customerId)
                && Objects.equals(address, that.address)
                && Objects.equals(addedAt, that.addedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, address, addedAt);
    }

    @Override
    public String toString() {
        return "CustomerAddressAdded["
                + "customerId=" + customerId
                + ", address=" + address
                + ", addedAt=" + addedAt
                + ']';
    }
}
