package com.example.producer.controller;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.amqp.EventExchanges;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.publisher.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * T-3.5: Demo REST endpoint — maps a JSON body to OrderCreated (Protobuf) and publishes via EventPublisher.
 * Returns 201 on success, 400 on schema validation failure (spec §5).
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
        if (request.productId() == null || request.currency() == null
                || request.quantity() == null || request.quantity() <= 0
                || request.totalAmount() == null) {
            throw new SchemaValidationException(
                    "events.orders:OrderCreated",
                    "Missing or invalid required fields (productId, currency, quantity>0, totalAmount)");
        }
        OrderCreated event = OrderCreated.newBuilder()
                .setOrderId(UUID.randomUUID().toString())
                .setCustomerId(request.customerId())
                .setProductId(request.productId())
                .setQuantity(request.quantity())
                .setTotalAmount(request.totalAmount())
                .setCurrency(request.currency())
                .setCreatedAt(Instant.now().toString())
                .build();

        eventPublisher.publish(EventExchanges.EVENTS_EXCHANGE, event);
        log.info("Published OrderCreated orderId={}", event.getOrderId());
    }

    /**
     * T-5.7: bypass-validation poison demo — publishes a malformed payload directly to
     * events.exchange with valid X-Schema-* headers but garbage bytes. Consumer will fail
     * deserialization → DLQ_DIRECT routing. FOR DEMO/TEST USE ONLY.
     *
     * <p>curl -X POST http://localhost:8081/api/orders/poison
     */
    @PostMapping("/poison")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void publishPoison() {
        MessageProperties props = new MessageProperties();
        props.setContentType("application/x-protobuf");
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "PROTOBUF");
        props.setHeader(SchemaMessageHeaders.MESSAGE_ID, UUID.randomUUID().toString());
        byte[] garbage = "NOT_VALID_PROTOBUF_BYTES".getBytes(StandardCharsets.UTF_8);
        rabbitTemplate.send(EventExchanges.EVENTS_EXCHANGE, OrderEventRouting.ROUTING_KEY, new Message(garbage, props));
        log.warn("Published poison message to orders.created (bypass-validation demo)");
    }

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<String> handleValidation(SchemaValidationException ex) {
        log.warn("Schema validation failed: {}", ex.getMessage());
        return ResponseEntity.badRequest().body("Schema validation failed: " + ex.getMessage());
    }

    public record CreateOrderRequest(
            String customerId,
            String productId,
            Integer quantity,
            Double totalAmount,
            String currency) {}
}
