package com.example.contracts.customers;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.customers.topology.CustomerEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted when a customer's loyalty tier changes. Code-first source of truth: this annotated class
 * is the contract, and {@code customer-tier-changed.schema.json} is generated from it. Carries the
 * {@link CustomerTier} enum, rendered as a JSON Schema {@code enum}.
 */
@GenerateSchema
@EventMapping(
        groupId = "events.customers",
        exchange = CustomerEventRouting.EXCHANGE,
        routingKey = CustomerEventRouting.TIER_CHANGED_ROUTING_KEY)
@JsonClassDescription("Emitted when a customer's loyalty tier changes.")
public final class CustomerTierChanged {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the customer.")
    private final UUID customerId;

    @JsonPropertyDescription("Previous loyalty tier, if any.")
    private final CustomerTier previousTier;

    @NotNull
    @JsonPropertyDescription("New loyalty tier.")
    private final CustomerTier newTier;

    @JsonPropertyDescription("Instant the tier change took effect (ISO-8601).")
    private final Instant effectiveAt;

    @JsonCreator
    public CustomerTierChanged(
            @JsonProperty("customerId") UUID customerId,
            @JsonProperty("previousTier") CustomerTier previousTier,
            @JsonProperty("newTier") CustomerTier newTier,
            @JsonProperty("effectiveAt") Instant effectiveAt) {
        this.customerId = customerId;
        this.previousTier = previousTier;
        this.newTier = newTier;
        this.effectiveAt = effectiveAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public CustomerTier getPreviousTier() {
        return previousTier;
    }

    public CustomerTier getNewTier() {
        return newTier;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CustomerTierChanged that)) {
            return false;
        }
        return Objects.equals(customerId, that.customerId)
                && Objects.equals(previousTier, that.previousTier)
                && Objects.equals(newTier, that.newTier)
                && Objects.equals(effectiveAt, that.effectiveAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, previousTier, newTier, effectiveAt);
    }

    @Override
    public String toString() {
        return "CustomerTierChanged["
                + "customerId=" + customerId
                + ", previousTier=" + previousTier
                + ", newTier=" + newTier
                + ", effectiveAt=" + effectiveAt
                + ']';
    }
}
