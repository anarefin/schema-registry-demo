package com.example.contracts.payments.codefirst;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentProcessedEvent(
        @NotNull UUID paymentId,
        @NotNull UUID orderId,
        @NotNull @DecimalMin(value = "0.01", inclusive = false) BigDecimal amount,
        @NotNull @Size(min = 3, max = 3) String currency,
        @Pattern(regexp = "^(CARD|BANK_TRANSFER|WALLET)$") String paymentMethod,
        Instant processedAt
) {}
