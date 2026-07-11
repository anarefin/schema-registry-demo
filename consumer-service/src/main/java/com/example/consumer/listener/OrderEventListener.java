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
 * record. The queue is resolved internally from the event's {@code TypeMapping} — no queue name
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
                event.orderId(), event.customerId(), event.productId(), event.quantity());
    }

    @BitsEventHandler
    public void onOrderShipped(OrderShipped event) {
        log.info("Received OrderShipped orderId={} carrier={} tracking={}",
                event.orderId(), event.carrier(), event.trackingNumber());
    }

    @BitsEventHandler
    public void onOrderCancelled(OrderCancelled event) {
        log.info("Received OrderCancelled orderId={} reason={} refund={}",
                event.orderId(), event.reason(), event.refundAmount());
    }

    @BitsEventHandler
    public void onOrderFulfilled(OrderFulfilled event) {
        log.info("Received OrderFulfilled orderId={} buyer={} city={} method={}",
                event.orderId(), event.buyer().displayName(),
                event.shipping().city(), event.payment().method());
    }
}
