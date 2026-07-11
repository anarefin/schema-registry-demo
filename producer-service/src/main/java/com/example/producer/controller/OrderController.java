package com.example.producer.controller;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.OrderShipped;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.publisher.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    private final RabbitTemplate rabbitTemplate;

    public OrderController(EventPublisher eventPublisher, RabbitTemplate rabbitTemplate) {
        this.eventPublisher = eventPublisher;
        this.rabbitTemplate = rabbitTemplate;
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

    /**
     * Bypass-validation poison demo — publishes a malformed payload directly to
     * {@link OrderEventRouting#EXCHANGE} with valid X-Schema-* headers but garbage bytes. The
     * consumer fails schema validation (unparseable JSON) → DLQ. FOR DEMO/TEST USE ONLY.
     *
     * <p>curl -X POST http://localhost:8081/api/orders/poison
     */
    @PostMapping("/poison")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void publishPoison() {
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        byte[] garbage = "{NOT_VALID_JSON".getBytes(StandardCharsets.UTF_8);
        rabbitTemplate.send(OrderEventRouting.EXCHANGE, OrderEventRouting.CREATED_ROUTING_KEY,
                new Message(garbage, props));
        log.warn("Published poison message to orders.created (bypass-validation demo)");
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
}
