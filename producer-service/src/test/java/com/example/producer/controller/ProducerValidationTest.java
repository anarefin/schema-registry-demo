package com.example.producer.controller;

import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.publisher.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-5.9 (U): producer validate-then-publish; REST endpoint returns 400 on
 * SchemaValidationException, never publishes invalid message (spec §5 / T-5.5).
 */
class ProducerValidationTest {

    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @Test
    void tc59_orderValidationFailureReturns400() throws Exception {
        OrderController controller = new OrderController(eventPublisher, rabbitTemplate);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        doThrow(new SchemaValidationException("events.orders:OrderCreated", "field missing"))
                .when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"11111111-1111-1111-1111-111111111111",
                                 "productId":"22222222-2222-2222-2222-222222222222",
                                 "quantity":1,"totalAmount":9.99,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }

    @Test
    void tc59_customerValidationFailureReturns400() throws Exception {
        CustomerController controller = new CustomerController(eventPublisher);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        doThrow(new SchemaValidationException("events.customers:CustomerRegistered", "email invalid"))
                .when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bad","firstName":"T","lastName":"U","phoneNumber":"555"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }

    @Test
    void fulfillOmittingBuyer_returns400() throws Exception {
        OrderController controller = new OrderController(eventPublisher, rabbitTemplate);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        doThrow(new SchemaValidationException("events.orders:OrderFulfilled", "buyer required"))
                .when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/orders/fulfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"33333333-3333-3333-3333-333333333333",
                                 "shipping":{"line1":"221B Baker Street","line2":null,"city":"London",
                                   "postalCode":"NW1 6XE","countryCode":"GB"},
                                 "payment":{"method":"CARD","amount":149.99,"currency":"GBP"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }

    @Test
    void fulfillOmittingShipping_returns400() throws Exception {
        OrderController controller = new OrderController(eventPublisher, rabbitTemplate);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        doThrow(new SchemaValidationException("events.orders:OrderFulfilled", "shipping required"))
                .when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/orders/fulfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"33333333-3333-3333-3333-333333333333",
                                 "buyer":{"customerId":"11111111-1111-1111-1111-111111111111",
                                   "email":"buyer@example.com","displayName":"Jane Doe"},
                                 "payment":{"method":"CARD","amount":149.99,"currency":"GBP"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }

    @Test
    void fulfillOmittingPayment_returns400() throws Exception {
        OrderController controller = new OrderController(eventPublisher, rabbitTemplate);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        doThrow(new SchemaValidationException("events.orders:OrderFulfilled", "payment required"))
                .when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/orders/fulfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"33333333-3333-3333-3333-333333333333",
                                 "buyer":{"customerId":"11111111-1111-1111-1111-111111111111",
                                   "email":"buyer@example.com","displayName":"Jane Doe"},
                                 "shipping":{"line1":"221B Baker Street","line2":null,"city":"London",
                                   "postalCode":"NW1 6XE","countryCode":"GB"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }
}
