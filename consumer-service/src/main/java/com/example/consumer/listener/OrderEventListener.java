package com.example.consumer.listener;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.contracts.orders.OrderShipped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * T-3.5: one {@code @RabbitListener} per order queue, each receiving the typed code-first record.
 *
 * <p>Delivery is honest at-least-once — there is no consumer-side deduplication. Idempotency, if
 * required, is the responsibility of downstream handlers (out of POC scope).
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @RabbitListener(queues = OrderEventRouting.CREATED_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void onOrderCreated(OrderCreated event) {
        log.info("Received OrderCreated orderId={} customerId={} productId={} qty={}",
                event.orderId(), event.customerId(), event.productId(), event.quantity());
    }

    @RabbitListener(queues = OrderEventRouting.SHIPPED_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void onOrderShipped(OrderShipped event) {
        log.info("Received OrderShipped orderId={} carrier={} tracking={}",
                event.orderId(), event.carrier(), event.trackingNumber());
    }

    @RabbitListener(queues = OrderEventRouting.CANCELLED_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void onOrderCancelled(OrderCancelled event) {
        log.info("Received OrderCancelled orderId={} reason={} refund={}",
                event.orderId(), event.reason(), event.refundAmount());
    }
}
