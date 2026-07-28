package com.example.contracts.orders;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Payment information embedded in {@link OrderFulfilled}; its schema is inlined into the owning
 * event's generated schema.
 */
@JsonClassDescription("Payment details for the fulfilled order.")
public final class PaymentDetails {

    @NotNull
    @JsonPropertyDescription("Payment method used, e.g. CARD or PAYPAL.")
    private final String method;

    @NotNull
    @DecimalMin(value = "0.01", inclusive = false)
    @JsonPropertyDescription("Amount charged; must be greater than 0.01.")
    private final BigDecimal amount;

    @NotNull
    @Size(min = 3, max = 3)
    @JsonPropertyDescription("ISO-4217 three-letter currency code, e.g. USD.")
    private final String currency;

    @JsonCreator
    public PaymentDetails(
            @JsonProperty("method") String method,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency) {
        this.method = method;
        this.amount = amount;
        this.currency = currency;
    }

    public String getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaymentDetails that)) {
            return false;
        }
        return Objects.equals(method, that.method)
                && Objects.equals(amount, that.amount)
                && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, amount, currency);
    }

    @Override
    public String toString() {
        return "PaymentDetails["
                + "method=" + method
                + ", amount=" + amount
                + ", currency=" + currency
                + ']';
    }
}
