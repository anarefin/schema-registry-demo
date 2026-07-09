package com.example.producer.controller;

import com.example.contracts.customers.Address;
import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerEventRouting;
import com.example.contracts.customers.CustomerTier;
import com.example.contracts.customers.CustomerTierChanged;
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
 * T-3.4: one demo REST endpoint per customer event — maps the request DTO to the code-first record
 * and publishes via {@link EventPublisher}. There are no manual field checks:
 * {@code SchemaAwareMessageConverter} is the single validation authority (a schema violation throws
 * {@code SchemaValidationException} → 400, and no message is emitted). Returns 201 on success.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final EventPublisher eventPublisher;

    public CustomerController(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void registerCustomer(@RequestBody RegisterCustomerRequest request) {
        CustomerRegistered event = new CustomerRegistered(
                UUID.randomUUID(),
                request.email(),
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                Instant.now());
        eventPublisher.publish(CustomerEventRouting.EXCHANGE, event);
        log.info("Published CustomerRegistered customerId={}", event.customerId());
    }

    @PostMapping("/address")
    @ResponseStatus(HttpStatus.CREATED)
    public void addAddress(@RequestBody AddAddressRequest request) {
        CustomerAddressAdded event = new CustomerAddressAdded(
                request.customerId(),
                request.address(),
                Instant.now());
        eventPublisher.publish(CustomerEventRouting.EXCHANGE, event);
        log.info("Published CustomerAddressAdded customerId={}", event.customerId());
    }

    @PostMapping("/tier")
    @ResponseStatus(HttpStatus.CREATED)
    public void changeTier(@RequestBody ChangeTierRequest request) {
        CustomerTierChanged event = new CustomerTierChanged(
                request.customerId(),
                request.previousTier(),
                request.newTier(),
                Instant.now());
        eventPublisher.publish(CustomerEventRouting.EXCHANGE, event);
        log.info("Published CustomerTierChanged customerId={} newTier={}",
                event.customerId(), event.newTier());
    }

    public record RegisterCustomerRequest(
            String email,
            String firstName,
            String lastName,
            String phoneNumber) {}

    public record AddAddressRequest(
            UUID customerId,
            Address address) {}

    public record ChangeTierRequest(
            UUID customerId,
            CustomerTier previousTier,
            CustomerTier newTier) {}
}
