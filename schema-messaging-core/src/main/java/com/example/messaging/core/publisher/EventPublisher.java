package com.example.messaging.core.publisher;

import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

/**
 * Wraps {@link RabbitTemplate} + {@link MessageConverter} for schema-governed publishing (T-1.10).
 *
 * <p>The converter handles validation → serialization → header population.
 * Routing key is read from the registered {@link TypeMapping}.
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    private final TypeMappingRegistry typeMappingRegistry;

    public EventPublisher(
            RabbitTemplate rabbitTemplate,
            MessageConverter messageConverter,
            TypeMappingRegistry typeMappingRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    /**
     * Validate, serialize, and publish {@code event} to the configured exchange.
     * The routing key is taken from the event's {@link TypeMapping}.
     *
     * @param exchange target AMQP exchange
     * @param event the domain object to publish (must have a registered TypeMapping)
     */
    public void publish(String exchange, Object event) {
        TypeMapping mapping = typeMappingRegistry.findByJavaType(event.getClass())
                .orElseThrow(() -> new IllegalStateException(
                        "No TypeMapping for " + event.getClass().getName()));

        MessageProperties props = new MessageProperties();
        Message message = messageConverter.toMessage(event, props);

        log.info("Publishing {} to exchange={} routingKey={}",
                event.getClass().getSimpleName(), exchange, mapping.routingKey());
        rabbitTemplate.send(exchange, mapping.routingKey(), message);
    }
}
