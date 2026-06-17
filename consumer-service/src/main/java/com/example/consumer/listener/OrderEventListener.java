package com.example.consumer.listener;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.messaging.core.consumer.IdempotencyFilter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * T-3.6 / T-5.6: Consumer for OrderCreated events with idempotency guard on X-Message-Id.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final IdempotencyFilter idempotencyFilter;

    public OrderEventListener(IdempotencyFilter idempotencyFilter) {
        this.idempotencyFilter = idempotencyFilter;
    }

    @RabbitListener(queues = OrderEventRouting.QUEUE_NAME,
                    containerFactory = "rabbitListenerContainerFactory")
    public void onOrderCreated(
            OrderCreated event,
            @Header(value = SchemaMessageHeaders.MESSAGE_ID, required = false) String messageId) {
        if (idempotencyFilter.alreadyProcessed(messageId)) {
            log.info("Duplicate OrderCreated messageId={}, skipping", messageId);
            return;
        }
        log.info("Received OrderCreated orderId={} customerId={} productId={} qty={}",
                event.getOrderId(),
                event.getCustomerId(),
                event.getProductId(),
                event.getQuantity());
        idempotencyFilter.markProcessed(messageId);
    }
}
