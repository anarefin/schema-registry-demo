package com.example.contracts.customers;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.customers.topology.CustomerEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted when a new customer registers. Code-first source of truth: this annotated class is the
 * contract, and {@code customer-registered.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@EventMapping(
        groupId = "events.customers",
        exchange = CustomerEventRouting.EXCHANGE,
        routingKey = CustomerEventRouting.REGISTERED_ROUTING_KEY)
@JsonClassDescription("Emitted when a new customer registers.")
public final class CustomerRegistered {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the customer.")
    private final UUID customerId;

    @NotNull
    @Email
    @JsonPropertyDescription("Customer email address.")
    private final String email;

    @NotNull
    @Size(min = 1, max = 100)
    @JsonPropertyDescription("Customer given name.")
    private final String firstName;

    @NotNull
    @Size(min = 1, max = 100)
    @JsonPropertyDescription("Customer family name.")
    private final String lastName;

    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$")
    @JsonPropertyDescription("Optional phone number in E.164 format.")
    private final String phoneNumber;

    @JsonPropertyDescription("Instant the customer registered (ISO-8601).")
    private final Instant registeredAt;

    @JsonCreator
    public CustomerRegistered(
            @JsonProperty("customerId") UUID customerId,
            @JsonProperty("email") String email,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("phoneNumber") String phoneNumber,
            @JsonProperty("registeredAt") Instant registeredAt) {
        this.customerId = customerId;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phoneNumber = phoneNumber;
        this.registeredAt = registeredAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CustomerRegistered that)) {
            return false;
        }
        return Objects.equals(customerId, that.customerId)
                && Objects.equals(email, that.email)
                && Objects.equals(firstName, that.firstName)
                && Objects.equals(lastName, that.lastName)
                && Objects.equals(phoneNumber, that.phoneNumber)
                && Objects.equals(registeredAt, that.registeredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerId, email, firstName, lastName, phoneNumber, registeredAt);
    }

    @Override
    public String toString() {
        return "CustomerRegistered["
                + "customerId=" + customerId
                + ", email=" + email
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", phoneNumber=" + phoneNumber
                + ", registeredAt=" + registeredAt
                + ']';
    }
}
