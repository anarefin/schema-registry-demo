package com.example.messaging.core.publisher;

import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Wraps {@link RabbitTemplate} + {@link SchemaAwareMessageConverter} for schema-governed
 * publishing (T-1.10).
 *
 * <p>The converter handles validation → serialization → header population.
 * Exchange and routing key are both read from the event's registered {@link TypeMapping} —
 * the caller supplies only the event.
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final SchemaAwareMessageConverter messageConverter;
    private final TypeMappingRegistry typeMappingRegistry;

    public EventPublisher(
            RabbitTemplate rabbitTemplate,
            SchemaAwareMessageConverter messageConverter,
            TypeMappingRegistry typeMappingRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
        this.typeMappingRegistry = typeMappingRegistry;
    }

    /**
     * Validate, serialize, and publish {@code event}. Both the exchange and routing key are
     * taken from the event's {@link TypeMapping}.
     *
     * @param event the domain object to publish (must have a registered TypeMapping)
     */
    public void publish(Object event) {
        TypeMapping mapping = typeMappingRegistry.findByJavaType(event.getClass())
                .orElseThrow(() -> new IllegalStateException(
                        "No TypeMapping for " + event.getClass().getName()));

        MessageProperties props = new MessageProperties();
        Message message = messageConverter.toMessage(event, props);

        log.info("Publishing {} to exchange={} routingKey={}",
                event.getClass().getSimpleName(), mapping.exchange(), mapping.routingKey());
        rabbitTemplate.send(mapping.exchange(), mapping.routingKey(), message);
    }
}
