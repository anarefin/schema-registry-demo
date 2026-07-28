package com.example.contracts.orders;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.orders.topology.OrderEventRouting;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted when a new order is placed. Code-first source of truth: this annotated class is the
 * contract, and {@code order-created.schema.json} is generated from it (never hand-edited).
 */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.CREATED_ROUTING_KEY)
@JsonClassDescription("Emitted when a customer places a new order.")
public final class OrderCreated {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the order.")
    private final UUID orderId;

    @NotNull
    @JsonPropertyDescription("Identifier of the customer who placed the order.")
    private final UUID customerId;

    @NotNull
    @JsonPropertyDescription("Identifier of the ordered product.")
    private final UUID productId;

    @NotNull
    @Min(1)
    @JsonPropertyDescription("Number of units ordered; must be at least 1.")
    private final Integer quantity;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = false)
    @JsonPropertyDescription("Order total in the given currency; must be greater than 0.01.")
    private final BigDecimal totalAmount;

    @NotNull
    @Size(min = 3, max = 3)
    @JsonPropertyDescription("ISO-4217 three-letter currency code, e.g. USD.")
    private final String currency;

    @JsonPropertyDescription("Instant the order was created (ISO-8601).")
    private final Instant createdAt;

    @JsonCreator
    public OrderCreated(
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("customerId") UUID customerId,
            @JsonProperty("productId") UUID productId,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("currency") String currency,
            @JsonProperty("createdAt") Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderCreated that)) {
            return false;
        }
        return Objects.equals(orderId, that.orderId)
                && Objects.equals(customerId, that.customerId)
                && Objects.equals(productId, that.productId)
                && Objects.equals(quantity, that.quantity)
                && Objects.equals(totalAmount, that.totalAmount)
                && Objects.equals(currency, that.currency)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, customerId, productId, quantity, totalAmount, currency, createdAt);
    }

    @Override
    public String toString() {
        return "OrderCreated["
                + "orderId=" + orderId
                + ", customerId=" + customerId
                + ", productId=" + productId
                + ", quantity=" + quantity
                + ", totalAmount=" + totalAmount
                + ", currency=" + currency
                + ", createdAt=" + createdAt
                + ']';
    }
}
