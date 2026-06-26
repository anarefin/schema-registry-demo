package com.example.consumer.listener;

import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * T-2.6: Consumer for CustomerRegistered events.
 *
 * <p>Delivery is honest at-least-once — there is no consumer-side deduplication. Idempotency,
 * if required, is the responsibility of downstream handlers (out of POC scope).
 */
@Component
public class CustomerEventListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventListener.class);

    @RabbitListener(queues = CustomerEventRouting.QUEUE_NAME,
                    containerFactory = "rabbitListenerContainerFactory")
    public void onCustomerRegistered(CustomerRegistered event) {
        log.info("Received CustomerRegistered customerId={} email={} name={} {}",
                event.getCustomerId(),
                event.getEmail(),
                event.getFirstName(),
                event.getLastName());
    }
}
