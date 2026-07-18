package com.example.producer.controller;

import com.example.contracts.orders.topology.OrderEventRouting;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Bypass-validation poison demo — publishes malformed payload with valid X-Schema-* headers.
 * Gated behind {@code events.demo.poison-endpoint=true} (off by default; on in local yml).
 */
@RestController
@RequestMapping("/api/orders")
@ConditionalOnProperty(name = "events.demo.poison-endpoint", havingValue = "true")
public class PoisonOrderController {

    private static final Logger log = LoggerFactory.getLogger(PoisonOrderController.class);

    private final RabbitTemplate rabbitTemplate;

    public PoisonOrderController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * curl -X POST http://localhost:8081/api/orders/poison
     * (requires {@code events.demo.poison-endpoint=true})
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
}
