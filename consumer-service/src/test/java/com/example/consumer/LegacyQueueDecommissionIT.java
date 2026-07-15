package com.example.consumer;

import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderEventRouting;
import com.example.amqp.topology.TopologyNaming;
import com.example.messaging.core.converter.SchemaMessageHeaders;
import com.example.consumer.support.PublisherOwnedExchanges;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves startup decommissions pre-per-service shared-domain queues on a broker that still carries
 * the old topology, and that published events no longer accumulate on those legacy names.
 */
@SpringBootTest
@Testcontainers
@Import(PublisherOwnedExchanges.class)
class LegacyQueueDecommissionIT {

    private static final String SERVICE_NAME = "consumer-service";
    private static final String LEGACY_MAIN_QUEUE = TopologyNaming.queueName(OrderEventRouting.CREATED_ROUTING_KEY);
    private static final String PER_SERVICE_QUEUE = TopologyNaming.serviceQueueName(
            OrderEventRouting.CREATED_ROUTING_KEY, SERVICE_NAME);

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void seedLegacySharedDomainTopologyBeforeContextRefresh(DynamicPropertyRegistry registry) {
        // Runs after the container is up but before Spring refreshes — so legacy queues exist when
        // ServiceQueueTopologyAutoConfiguration starts and must be decommissioned.
        seedLegacySharedDomainTopology();
    }

    @Test
    void startupRemovesLegacySharedDomainQueues() {
        assertThat(rabbitAdmin.getQueueProperties(LEGACY_MAIN_QUEUE)).isNull();
        assertThat(rabbitAdmin.getQueueProperties(PER_SERVICE_QUEUE)).isNotNull();
    }

    @Test
    void publishedEventsDoNotAccumulateOnLegacyQueueNames() throws Exception {
        OrderCreated event = new OrderCreated(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                new BigDecimal("9.99"),
                "USD",
                Instant.parse("2026-05-31T00:00:00Z"));
        byte[] body = objectMapper.writeValueAsBytes(event);

        // Send the pre-serialized bytes with X-Schema-* headers set directly. Using send() with a
        // pre-built Message bypasses the RabbitTemplate's SchemaAwareMessageConverter — convertAndSend
        // would run the byte[] through toMessage and fail with "No TypeMapping registered for [B".
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setHeader(SchemaMessageHeaders.GROUP_ID, "events.orders");
        props.setHeader(SchemaMessageHeaders.ARTIFACT_ID, "OrderCreated");
        props.setHeader(SchemaMessageHeaders.TYPE, "JSON");
        rabbitTemplate.send(
                OrderEventRouting.EXCHANGE,
                OrderEventRouting.CREATED_ROUTING_KEY,
                new Message(body, props));

        // The legacy queue was decommissioned at startup; publishing to the exchange never
        // recreates a queue, so the event routes only to the per-service queue and the legacy
        // name stays absent. Asserting non-existence (rather than receive(), which would throw
        // 404 NOT_FOUND on the missing queue) is the honest check that nothing accumulates there.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rabbitAdmin.getQueueProperties(LEGACY_MAIN_QUEUE)).isNull());
    }

    private static void seedLegacySharedDomainTopology() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                rabbitMQ.getHost(), rabbitMQ.getAmqpPort());
        connectionFactory.setUsername(rabbitMQ.getAdminUsername());
        connectionFactory.setPassword(rabbitMQ.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);

        TopicExchange mainExchange = new TopicExchange(OrderEventRouting.EXCHANGE, true, false);
        admin.declareExchange(mainExchange);

        Queue legacyMain = QueueBuilder.durable(LEGACY_MAIN_QUEUE).build();
        admin.declareQueue(legacyMain);
        admin.declareBinding(BindingBuilder.bind(legacyMain)
                .to(mainExchange)
                .with(OrderEventRouting.CREATED_ROUTING_KEY));

        connectionFactory.destroy();
    }
}
