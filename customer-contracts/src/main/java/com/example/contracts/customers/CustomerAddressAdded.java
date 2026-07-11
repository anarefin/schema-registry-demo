package com.example.contracts.customers;

import com.example.amqp.topology.mapping.GenerateSchema;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when an address is added to a customer. Code-first source of truth: this annotated record
 * is the contract, and {@code customer-address-added.schema.json} is generated from it. Carries a
 * nested {@link Address} value object, inlined into the generated schema.
 */
@GenerateSchema
@JsonClassDescription("Emitted when a customer adds a postal address.")
public record CustomerAddressAdded(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the customer.")
        UUID customerId,

        @NotNull
        @Valid
        @JsonPropertyDescription("The address that was added.")
        Address address,

        @JsonPropertyDescription("Instant the address was added (ISO-8601).")
        Instant addedAt
) {}
