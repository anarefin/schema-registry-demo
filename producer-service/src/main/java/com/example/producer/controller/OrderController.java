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
 * T-3.4: one demo REST endpoint per order event — maps the request DTO to the code-first record and
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
                request.customerId(),
                request.productId(),
                request.quantity(),
                request.totalAmount(),
                request.currency(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderCreated orderId={}", event.orderId());
    }

    @PostMapping("/ship")
    @ResponseStatus(HttpStatus.CREATED)
    public void shipOrder(@RequestBody ShipOrderRequest request) {
        OrderShipped event = new OrderShipped(
                request.orderId(),
                request.trackingNumber(),
                request.carrier(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderShipped orderId={}", event.orderId());
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public void cancelOrder(@RequestBody CancelOrderRequest request) {
        OrderCancelled event = new OrderCancelled(
                request.orderId(),
                request.reason(),
                request.refundAmount(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderCancelled orderId={}", event.orderId());
    }

    @PostMapping("/fulfill")
    @ResponseStatus(HttpStatus.CREATED)
    public void fulfillOrder(@RequestBody FulfillOrderRequest request) {
        // Pass null nested DTOs through so schema validation (not NPE) owns the 400.
        OrderBuyer buyer = request.buyer() == null
                ? null
                : new OrderBuyer(request.buyer().customerId(), request.buyer().email(),
                        request.buyer().displayName());
        ShippingAddress shipping = request.shipping() == null
                ? null
                : new ShippingAddress(request.shipping().line1(), request.shipping().line2(),
                        request.shipping().city(), request.shipping().postalCode(),
                        request.shipping().countryCode());
        PaymentDetails payment = request.payment() == null
                ? null
                : new PaymentDetails(request.payment().method(), request.payment().amount(),
                        request.payment().currency());
        OrderFulfilled event = new OrderFulfilled(
                request.orderId(), buyer, shipping, payment, Instant.now());
        eventPublisher.publish(event);
        log.info("Published OrderFulfilled orderId={}", event.orderId());
    }

    public record CreateOrderRequest(
            UUID customerId,
            UUID productId,
            Integer quantity,
            BigDecimal totalAmount,
            String currency) {}

    public record ShipOrderRequest(
            UUID orderId,
            String trackingNumber,
            String carrier) {}

    public record CancelOrderRequest(
            UUID orderId,
            String reason,
            BigDecimal refundAmount) {}

    public record FulfillOrderRequest(
            UUID orderId,
            BuyerRequest buyer,
            ShippingRequest shipping,
            PaymentRequest payment) {}

    public record BuyerRequest(
            UUID customerId,
            String email,
            String displayName) {}

    public record ShippingRequest(
            String line1,
            String line2,
            String city,
            String postalCode,
            String countryCode) {}

    public record PaymentRequest(
            String method,
            BigDecimal amount,
            String currency) {}
}
