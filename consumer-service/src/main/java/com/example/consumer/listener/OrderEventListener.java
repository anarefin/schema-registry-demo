package com.example.consumer.listener;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer for OrderCreated events. The schema-aware listener factory has already validated
 * and deserialized the message before this method runs; a thrown exception is routed to the
 * retry queue or DLQ by the DlxRoutingAdvice.
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
