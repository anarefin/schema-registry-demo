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
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        doThrow(new SchemaValidationException("events.orders:OrderCreated", "field missing"))
                .when(eventPublisher).publish(any(), any());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"c1","productId":"p1","quantity":1,
                                 "totalAmount":9.99,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }

    @Test
    void tc59_customerValidationFailureReturns400() throws Exception {
        CustomerController controller = new CustomerController(eventPublisher);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        doThrow(new SchemaValidationException("events.customers:CustomerRegistered", "email invalid"))
                .when(eventPublisher).publish(any(), any());

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bad","firstName":"T","lastName":"U","phoneNumber":"555"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema validation failed")));
    }
}
