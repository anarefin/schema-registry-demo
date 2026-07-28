package com.example.producer.controller;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderFulfilled;
import com.example.contracts.orders.OrderShipped;
import com.example.contracts.orders.OrderBuyer;
import com.example.contracts.orders.PaymentDetails;
import com.example.contracts.orders.ShippingAddress;
import com.example.messaging.core.publisher.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * T-3.4: one demo REST endpoint per order event — maps the request DTO to the code-first class and
 * publishes via {@link EventPublisher}. There are no manual field checks:
 * {@code SchemaAwareMessageConverter} is the single validation authority (a schema violation throws
 * {@code SchemaValidationException} → 400, and no message is emitted). Returns 201 on success.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final EventPublisher eventPublisher;

    public OrderController(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void createOrder(@RequestBody CreateOrderRequest request) {
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(),
                request.getCustomerId(),
                request.getProductId(),
                request.getQuantity(),
                request.getTotalAmount(),
                request.getCurrency(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderCreated orderId={}", event.getOrderId());
    }

    @PostMapping("/ship")
    @ResponseStatus(HttpStatus.CREATED)
    public void shipOrder(@RequestBody ShipOrderRequest request) {
        OrderShipped event = new OrderShipped(
                request.getOrderId(),
                request.getTrackingNumber(),
                request.getCarrier(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderShipped orderId={}", event.getOrderId());
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public void cancelOrder(@RequestBody CancelOrderRequest request) {
        OrderCancelled event = new OrderCancelled(
                request.getOrderId(),
                request.getReason(),
                request.getRefundAmount(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderCancelled orderId={}", event.getOrderId());
    }

    @PostMapping("/fulfill")
    @ResponseStatus(HttpStatus.CREATED)
    public void fulfillOrder(@RequestBody FulfillOrderRequest request) {
        // Pass null nested DTOs through so schema validation (not NPE) owns the 400.
        OrderBuyer buyer = request.getBuyer() == null
                ? null
                : new OrderBuyer(request.getBuyer().getCustomerId(), request.getBuyer().getEmail(),
                        request.getBuyer().getDisplayName());
        ShippingAddress shipping = request.getShipping() == null
                ? null
                : new ShippingAddress(request.getShipping().getLine1(), request.getShipping().getLine2(),
                        request.getShipping().getCity(), request.getShipping().getPostalCode(),
                        request.getShipping().getCountryCode());
        PaymentDetails payment = request.getPayment() == null
                ? null
                : new PaymentDetails(request.getPayment().getMethod(), request.getPayment().getAmount(),
                        request.getPayment().getCurrency());
        OrderFulfilled event = new OrderFulfilled(
                request.getOrderId(), buyer, shipping, payment, Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderFulfilled orderId={}", event.getOrderId());
    }

    public static final class CreateOrderRequest {

        private final UUID customerId;
        private final UUID productId;
        private final Integer quantity;
        private final BigDecimal totalAmount;
        private final String currency;

        public CreateOrderRequest(
                UUID customerId,
                UUID productId,
                Integer quantity,
                BigDecimal totalAmount,
                String currency) {
            this.customerId = customerId;
            this.productId = productId;
            this.quantity = quantity;
            this.totalAmount = totalAmount;
            this.currency = currency;
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
    }

    public static final class ShipOrderRequest {

        private final UUID orderId;
        private final String trackingNumber;
        private final String carrier;

        public ShipOrderRequest(UUID orderId, String trackingNumber, String carrier) {
            this.orderId = orderId;
            this.trackingNumber = trackingNumber;
            this.carrier = carrier;
        }

        public UUID getOrderId() {
            return orderId;
        }

        public String getTrackingNumber() {
            return trackingNumber;
        }

        public String getCarrier() {
            return carrier;
        }
    }

    public static final class CancelOrderRequest {

        private final UUID orderId;
        private final String reason;
        private final BigDecimal refundAmount;

        public CancelOrderRequest(UUID orderId, String reason, BigDecimal refundAmount) {
            this.orderId = orderId;
            this.reason = reason;
            this.refundAmount = refundAmount;
        }

        public UUID getOrderId() {
            return orderId;
        }

        public String getReason() {
            return reason;
        }

        public BigDecimal getRefundAmount() {
            return refundAmount;
        }
    }

    public static final class FulfillOrderRequest {

        private final UUID orderId;
        private final BuyerRequest buyer;
        private final ShippingRequest shipping;
        private final PaymentRequest payment;

        public FulfillOrderRequest(
                UUID orderId,
                BuyerRequest buyer,
                ShippingRequest shipping,
                PaymentRequest payment) {
            this.orderId = orderId;
            this.buyer = buyer;
            this.shipping = shipping;
            this.payment = payment;
        }

        public UUID getOrderId() {
            return orderId;
        }

        public BuyerRequest getBuyer() {
            return buyer;
        }

        public ShippingRequest getShipping() {
            return shipping;
        }

        public PaymentRequest getPayment() {
            return payment;
        }
    }

    public static final class BuyerRequest {

        private final UUID customerId;
        private final String email;
        private final String displayName;

        public BuyerRequest(UUID customerId, String email, String displayName) {
            this.customerId = customerId;
            this.email = email;
            this.displayName = displayName;
        }

        public UUID getCustomerId() {
            return customerId;
        }

        public String getEmail() {
            return email;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static final class ShippingRequest {

        private final String line1;
        private final String line2;
        private final String city;
        private final String postalCode;
        private final String countryCode;

        public ShippingRequest(
                String line1,
                String line2,
                String city,
                String postalCode,
                String countryCode) {
            this.line1 = line1;
            this.line2 = line2;
            this.city = city;
            this.postalCode = postalCode;
            this.countryCode = countryCode;
        }

        public String getLine1() {
            return line1;
        }

        public String getLine2() {
            return line2;
        }

        public String getCity() {
            return city;
        }

        public String getPostalCode() {
            return postalCode;
        }

        public String getCountryCode() {
            return countryCode;
        }
    }

    public static final class PaymentRequest {

        private final String method;
        private final BigDecimal amount;
        private final String currency;

        public PaymentRequest(String method, BigDecimal amount, String currency) {
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
    }
}
