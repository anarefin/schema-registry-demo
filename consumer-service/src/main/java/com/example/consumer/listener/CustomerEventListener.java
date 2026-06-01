package com.example.consumer.listener;

import com.example.contracts.customers.CustomerRegistered;
import com.example.messaging.core.consumer.IdempotencyFilter;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * T-2.6 / T-5.6: Consumer for CustomerRegistered events with idempotency guard on X-Message-Id.
 */
@Component
public class CustomerEventListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventListener.class);

    private final IdempotencyFilter idempotencyFilter;

    public CustomerEventListener(IdempotencyFilter idempotencyFilter) {
        this.idempotencyFilter = idempotencyFilter;
    }

    @RabbitListener(queues = "customers.registered.queue",
                    containerFactory = "rabbitListenerContainerFactory")
    public void onCustomerRegistered(
            CustomerRegistered event,
            @Header(value = SchemaMessageHeaders.MESSAGE_ID, required = false) String messageId) {
        if (idempotencyFilter.isDuplicate(messageId)) {
            log.info("Duplicate CustomerRegistered messageId={}, skipping", messageId);
            return;
        }
        log.info("Received CustomerRegistered customerId={} email={} name={} {}",
                event.getCustomerId(),
                event.getEmail(),
                event.getFirstName(),
                event.getLastName());
    }
}
