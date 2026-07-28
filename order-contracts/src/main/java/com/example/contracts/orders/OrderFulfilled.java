package com.example.contracts.orders;

import com.example.amqp.topology.mapping.EventMapping;
import com.example.amqp.topology.mapping.GenerateSchema;
import com.example.contracts.orders.topology.OrderEventRouting;
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
 * Emitted when an order is fulfilled. Code-first source of truth: this annotated class is the
 * contract, and {@code order-fulfilled.schema.json} is generated from it. Carries three nested
 * value objects ({@link OrderBuyer}, {@link ShippingAddress}, {@link PaymentDetails}), each
 * inlined into the generated schema.
 */
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.FULFILLED_ROUTING_KEY)
@JsonClassDescription("Emitted when an order is fulfilled and ready for delivery.")
public final class OrderFulfilled {

    @NotNull
    @JsonPropertyDescription("Unique identifier of the order.")
    private final UUID orderId;

    @NotNull
    @Valid
    @JsonPropertyDescription("The customer who placed the order.")
    private final OrderBuyer buyer;

    @NotNull
    @Valid
    @JsonPropertyDescription("The shipping destination for the order.")
    private final ShippingAddress shipping;

    @NotNull
    @Valid
    @JsonPropertyDescription("Payment details for the fulfilled order.")
    private final PaymentDetails payment;

    @JsonPropertyDescription("Instant the order was fulfilled (ISO-8601).")
    private final Instant fulfilledAt;

    @JsonCreator
    public OrderFulfilled(
            @JsonProperty("orderId") UUID orderId,
            @JsonProperty("buyer") OrderBuyer buyer,
            @JsonProperty("shipping") ShippingAddress shipping,
            @JsonProperty("payment") PaymentDetails payment,
            @JsonProperty("fulfilledAt") Instant fulfilledAt) {
        this.orderId = orderId;
        this.buyer = buyer;
        this.shipping = shipping;
        this.payment = payment;
        this.fulfilledAt = fulfilledAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderBuyer getBuyer() {
        return buyer;
    }

    public ShippingAddress getShipping() {
        return shipping;
    }

    public PaymentDetails getPayment() {
        return payment;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderFulfilled that)) {
            return false;
        }
        return Objects.equals(orderId, that.orderId)
                && Objects.equals(buyer, that.buyer)
                && Objects.equals(shipping, that.shipping)
                && Objects.equals(payment, that.payment)
                && Objects.equals(fulfilledAt, that.fulfilledAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, buyer, shipping, payment, fulfilledAt);
    }

    @Override
    public String toString() {
        return "OrderFulfilled["
                + "orderId=" + orderId
                + ", buyer=" + buyer
                + ", shipping=" + shipping
                + ", payment=" + payment
                + ", fulfilledAt=" + fulfilledAt
                + ']';
    }
}
