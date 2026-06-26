package com.example.consumer.listener;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * T-3.6: Consumer for OrderCreated events.
 *
 * <p>Delivery is honest at-least-once — there is no consumer-side deduplication. Idempotency,
 * if required, is the responsibility of downstream handlers (out of POC scope).
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @RabbitListener(queues = OrderEventRouting.QUEUE_NAME,
                    containerFactory = "rabbitListenerContainerFactory")
    public void onOrderCreated(OrderCreated event) {
        log.info("Received OrderCreated orderId={} customerId={} productId={} qty={}",
                event.getOrderId(),
                event.getCustomerId(),
                event.getProductId(),
                event.getQuantity());
    }
}
