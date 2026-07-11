package com.example.contracts.customers;

import com.example.amqp.topology.mapping.GenerateSchema;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a customer's loyalty tier changes. Code-first source of truth: this annotated record
 * is the contract, and {@code customer-tier-changed.schema.json} is generated from it. Carries the
 * {@link CustomerTier} enum, rendered as a JSON Schema {@code enum}.
 */
@GenerateSchema
@JsonClassDescription("Emitted when a customer's loyalty tier changes.")
public record CustomerTierChanged(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the customer.")
        UUID customerId,

        @JsonPropertyDescription("Previous loyalty tier, if any.")
        CustomerTier previousTier,

        @NotNull
        @JsonPropertyDescription("New loyalty tier.")
        CustomerTier newTier,

        @JsonPropertyDescription("Instant the tier change took effect (ISO-8601).")
        Instant effectiveAt
) {}
