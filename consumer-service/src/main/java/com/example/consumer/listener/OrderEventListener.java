package com.example.consumer.listener;

import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderFulfilled;
import com.example.contracts.orders.OrderShipped;
import com.example.messaging.core.consumer.BitsEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T-3.5: one {@code @BitsEventHandler} per order event, each receiving the typed code-first
 * class. The queue is resolved internally from the event's {@code TypeMapping} — no queue name
 * or container factory needed here (spec {@code simplified-publish-and-listen.md} D3/D4).
 *
 * <p>Delivery is honest at-least-once — there is no consumer-side deduplication. Idempotency, if
 * required, is the responsibility of downstream handlers (out of POC scope).
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    @BitsEventHandler
    public void onOrderCreated(OrderCreated event) {
        log.info("Received OrderCreated orderId={} customerId={} productId={} qty={}",
                event.getOrderId(), event.getCustomerId(), event.getProductId(), event.getQuantity());
    }

    @BitsEventHandler
    public void onOrderShipped(OrderShipped event) {
        log.info("Received OrderShipped orderId={} carrier={} tracking={}",
                event.getOrderId(), event.getCarrier(), event.getTrackingNumber());
    }

    @BitsEventHandler
    public void onOrderCancelled(OrderCancelled event) {
        log.info("Received OrderCancelled orderId={} reason={} refund={}",
                event.getOrderId(), event.getReason(), event.getRefundAmount());
    }

    @BitsEventHandler
    public void onOrderFulfilled(OrderFulfilled event) {
        log.info("Received OrderFulfilled orderId={} buyer={} city={} method={}",
                event.getOrderId(), event.getBuyer().getDisplayName(),
                event.getShipping().getCity(), event.getPayment().getMethod());
    }
}
