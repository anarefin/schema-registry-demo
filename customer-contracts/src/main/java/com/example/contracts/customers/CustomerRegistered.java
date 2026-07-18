package com.example.contracts.customers;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.customers.topology.CustomerEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a new customer registers. Code-first source of truth: this annotated record is the
 * contract, and {@code customer-registered.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@EventMapping(
        groupId = "events.customers",
        exchange = CustomerEventRouting.EXCHANGE,
        routingKey = CustomerEventRouting.REGISTERED_ROUTING_KEY)
@JsonClassDescription("Emitted when a new customer registers.")
public record CustomerRegistered(

        @NotNull
        @JsonPropertyDescription("Unique identifier of the customer.")
        UUID customerId,

        @NotNull
        @Email
        @JsonPropertyDescription("Customer email address.")
        String email,

        @NotNull
        @Size(min = 1, max = 100)
        @JsonPropertyDescription("Customer given name.")
        String firstName,

        @NotNull
        @Size(min = 1, max = 100)
        @JsonPropertyDescription("Customer family name.")
        String lastName,

        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$")
        @JsonPropertyDescription("Optional phone number in E.164 format.")
        String phoneNumber,

        @JsonPropertyDescription("Instant the customer registered (ISO-8601).")
        Instant registeredAt
) {}
