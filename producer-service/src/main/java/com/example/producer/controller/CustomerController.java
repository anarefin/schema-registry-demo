package com.example.producer.controller;

import com.example.contracts.customers.Address;
import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerRegistered;
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
 * T-3.4: one demo REST endpoint per customer event — maps the request DTO to the code-first class
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
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhoneNumber(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published CustomerRegistered customerId={}", event.getCustomerId());
    }

    @PostMapping("/address")
    @ResponseStatus(HttpStatus.CREATED)
    public void addAddress(@RequestBody AddAddressRequest request) {
        CustomerAddressAdded event = new CustomerAddressAdded(
                request.getCustomerId(),
                request.getAddress(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published CustomerAddressAdded customerId={}", event.getCustomerId());
    }

    @PostMapping("/tier")
    @ResponseStatus(HttpStatus.CREATED)
    public void changeTier(@RequestBody ChangeTierRequest request) {
        CustomerTierChanged event = new CustomerTierChanged(
                request.getCustomerId(),
                request.getPreviousTier(),
                request.getNewTier(),
                Instant.now());
        eventPublisher.publish(event);
        log.info("Published CustomerTierChanged customerId={} newTier={}",
                event.getCustomerId(), event.getNewTier());
    }

    public static final class RegisterCustomerRequest {

        private final String email;
        private final String firstName;
        private final String lastName;
        private final String phoneNumber;

        public RegisterCustomerRequest(
                String email,
                String firstName,
                String lastName,
                String phoneNumber) {
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.phoneNumber = phoneNumber;
        }

        public String getEmail() {
            return email;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }
    }

    public static final class AddAddressRequest {

        private final UUID customerId;
        private final Address address;

        public AddAddressRequest(UUID customerId, Address address) {
            this.customerId = customerId;
            this.address = address;
        }

        public UUID getCustomerId() {
            return customerId;
        }

        public Address getAddress() {
            return address;
        }
    }

    public static final class ChangeTierRequest {

        private final UUID customerId;
        private final CustomerTier previousTier;
        private final CustomerTier newTier;

        public ChangeTierRequest(UUID customerId, CustomerTier previousTier, CustomerTier newTier) {
            this.customerId = customerId;
            this.previousTier = previousTier;
            this.newTier = newTier;
        }

        public UUID getCustomerId() {
            return customerId;
        }

        public CustomerTier getPreviousTier() {
            return previousTier;
        }

        public CustomerTier getNewTier() {
            return newTier;
        }
    }
}
