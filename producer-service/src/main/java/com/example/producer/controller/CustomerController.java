package com.example.producer.controller;

import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.amqp.EventExchanges;
import com.example.messaging.core.publisher.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * T-2.5: Demo REST endpoint — maps a JSON body to CustomerRegistered and publishes via EventPublisher.
 * Returns 201 on success, 400 on schema validation failure (spec §5).
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final EventPublisher eventPublisher;

    public CustomerController(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Register a new customer — validates against the JSON Schema before publishing.
     *
     * @param request incoming registration payload
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void registerCustomer(@RequestBody RegisterCustomerRequest request) {
        CustomerRegistered event = new CustomerRegistered();
        event.setCustomerId(UUID.randomUUID().toString());
        event.setEmail(request.email());
        event.setFirstName(request.firstName());
        event.setLastName(request.lastName());
        event.setPhoneNumber(request.phoneNumber());
        event.setRegisteredAt(Instant.now().toString());

        eventPublisher.publish(EventExchanges.EVENTS_EXCHANGE, event);
        log.info("Published CustomerRegistered customerId={}", event.getCustomerId());
    }

    public record RegisterCustomerRequest(
            String email,
            String firstName,
            String lastName,
            String phoneNumber) {}
}
