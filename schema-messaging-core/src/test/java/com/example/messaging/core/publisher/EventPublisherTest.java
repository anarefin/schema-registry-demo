package com.example.messaging.core.publisher;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    record Order(String id) {}

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private SchemaAwareMessageConverter messageConverter;

    @Test
    void publish_sendsToMappingExchangeAndRoutingKey() {
        TypeMapping mapping = new TypeMapping(Order.class,
                new SchemaCoordinates("events.orders", "Order"), SchemaType.JSON,
                "orders.created", "events.orders.exchange");
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of(mapping));
        EventPublisher publisher = new EventPublisher(rabbitTemplate, messageConverter, registry);

        Order event = new Order("o-1");
        Message message = new Message(new byte[0], new MessageProperties());
        when(messageConverter.toMessage(eq(event), any())).thenReturn(message);

        publisher.publish(event);

        verify(rabbitTemplate).send(eq("events.orders.exchange"), eq("orders.created"), eq(message));
    }

    @Test
    void publish_noMapping_throwsIllegalState() {
        TypeMappingRegistry registry = new TypeMappingRegistry(List.of());
        EventPublisher publisher = new EventPublisher(rabbitTemplate, messageConverter, registry);

        assertThatThrownBy(() -> publisher.publish(new Order("o-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No TypeMapping for")
                .hasMessageContaining(Order.class.getName());
    }
}
