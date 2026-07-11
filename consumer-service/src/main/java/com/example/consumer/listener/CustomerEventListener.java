package com.example.consumer.listener;

import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerTierChanged;
import com.example.messaging.core.consumer.BitsEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T-3.5: one {@code @BitsEventHandler} per customer event, each receiving the typed code-first
 * record. The queue is resolved internally from the event's {@code TypeMapping} — no queue name
 * or container factory needed here (spec {@code simplified-publish-and-listen.md} D3/D4).
 *
 * <p>Delivery is honest at-least-once — there is no consumer-side deduplication. Idempotency, if
 * required, is the responsibility of downstream handlers (out of POC scope).
 */
@Component
public class CustomerEventListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventListener.class);

    @BitsEventHandler
    public void onCustomerRegistered(CustomerRegistered event) {
        log.info("Received CustomerRegistered customerId={} email={} name={} {}",
                event.customerId(), event.email(), event.firstName(), event.lastName());
    }

    @BitsEventHandler
    public void onCustomerAddressAdded(CustomerAddressAdded event) {
        log.info("Received CustomerAddressAdded customerId={} city={} country={}",
                event.customerId(),
                event.address() != null ? event.address().city() : null,
                event.address() != null ? event.address().countryCode() : null);
    }

    @BitsEventHandler
    public void onCustomerTierChanged(CustomerTierChanged event) {
        log.info("Received CustomerTierChanged customerId={} {} -> {}",
                event.customerId(), event.previousTier(), event.newTier());
    }
}
