package com.example.messaging.core.converter;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.SchemaMessagingException;
import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.example.messaging.core.observability.SchemaMessagingMetrics;
import com.example.messaging.core.registry.SchemaResolver;
import com.example.messaging.core.serde.SerializationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// MDC is used to propagate the current trace ID as X-Correlation-Id when none is set.
// Falls back to a random UUID in non-tracing contexts.

/**
 * Spring AMQP {@link MessageConverter} that enforces schema governance (spec §10.1).
 *
 * <p><b>Produce ({@link #toMessage}):</b> lookup TypeMapping → resolve schema →
 * validate + serialize → populate {@code X-Schema-*} headers + content-type.
 * Validation failure throws {@link com.example.messaging.core.exception.SchemaValidationException}
 * and NO message is emitted.
 *
 * <p><b>Consume ({@link #fromMessage}):</b> read {@code X-Schema-*} headers → resolve
 * (prefer globalId to skip coordinate lookup) → dispatch to matching strategy → return typed object.
 */
public class SchemaAwareMessageConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(SchemaAwareMessageConverter.class);

    private final TypeMappingRegistry typeMappingRegistry;
    private final SchemaResolver schemaResolver;
    private final Map<SchemaType, SerializationStrategy> strategies;
    private final SchemaMessagingMetrics metrics;

    public SchemaAwareMessageConverter(
            TypeMappingRegistry typeMappingRegistry,
            SchemaResolver schemaResolver,
            List<SerializationStrategy> strategies,
            SchemaMessagingMetrics metrics) {
        this.typeMappingRegistry = typeMappingRegistry;
        this.schemaResolver = schemaResolver;
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(SerializationStrategy::schemaType, Function.identity()));
        this.metrics = metrics;
    }

    // ---- produce path -----------------------------------------------------

    @Override
    public Message toMessage(Object object, MessageProperties messageProperties)
            throws MessageConversionException {
        Class<?> type = object.getClass();
        TypeMapping mapping = typeMappingRegistry.findByJavaType(type)
                .orElseThrow(() -> new MessageConversionException(
                        "No TypeMapping registered for " + type.getName()));

        ResolvedSchema schema = schemaResolver.resolveByCoordinates(mapping.coordinates());
        SerializationStrategy strategy = strategyFor(mapping.schemaType());

        byte[] bytes;
        try {
            bytes = strategy.serialize(object, schema);
        } catch (SchemaValidationException e) {
            metrics.recordValidationFailure();
            metrics.recordPublishFailure(mapping.schemaType());
            throw e;
        } catch (SchemaMessagingException e) {
            metrics.recordPublishFailure(mapping.schemaType());
            throw e;
        } catch (Exception e) {
            metrics.recordPublishFailure(mapping.schemaType());
            throw new MessageConversionException("Serialization failed for " + type.getName(), e);
        }

        ensureMessageId(messageProperties);
        SchemaMessageHeaders.setSchemaHeaders(messageProperties, schema.globalId(), mapping.coordinates(), mapping.schemaType());
        metrics.recordPublish(mapping.schemaType());

        log.info("Serialized {} to {} bytes [globalId={}, routingKey={}]",
                type.getSimpleName(), bytes.length, schema.globalId(), mapping.routingKey());
        return new Message(bytes, messageProperties);
    }

    // ---- consume path -----------------------------------------------------

    @Override
    public Object fromMessage(Message message) throws MessageConversionException {
        MessageProperties props = message.getMessageProperties();

        String groupId = SchemaMessageHeaders.getGroupId(props);
        String artifactId = SchemaMessageHeaders.getArtifactId(props);
        SchemaType headerType = SchemaMessageHeaders.getSchemaType(props);

        // Validate type against registered TypeMapping (TC-1.11)
        TypeMapping mapping = typeMappingRegistry
                .findByGroupAndArtifact(groupId, artifactId)
                .orElseThrow(() -> new MessageConversionException(
                        "No TypeMapping for artifact " + groupId + ":" + artifactId));

        if (headerType != null && headerType != mapping.schemaType()) {
            throw new IncompatibleSchemaTypeException(
                    mapping.schemaType().name(), headerType.name(),
                    groupId + ":" + artifactId);
        }

        SchemaType effectiveType = headerType != null ? headerType : mapping.schemaType();
        ResolvedSchema schema = resolveSchema(props, effectiveType);
        SerializationStrategy strategy = strategyFor(effectiveType);

        byte[] body = message.getBody();
        try {
            Object result = strategy.deserialize(body, mapping.javaType(), schema);
            metrics.recordConsume(effectiveType);
            log.debug("Deserialized {} bytes → {} [globalId={}]",
                    body.length, mapping.javaType().getSimpleName(), schema.globalId());
            return result;
        } catch (SchemaMessagingException e) {
            metrics.recordConsumeFailure(effectiveType);
            throw e;
        } catch (Exception e) {
            metrics.recordConsumeFailure(effectiveType);
            throw new DeserializationException(mapping.javaType().getSimpleName(), e);
        }
    }

    // ---- private helpers --------------------------------------------------

    private ResolvedSchema resolveSchema(MessageProperties props, SchemaType schemaType) {
        Long globalId = SchemaMessageHeaders.getGlobalId(props);
        if (globalId != null) {
            // Fast path: content fetch via globalId, type known from header (spec §6)
            return schemaResolver.resolveByGlobalId(globalId, schemaType);
        }
        String groupId = SchemaMessageHeaders.getGroupId(props);
        String artifactId = SchemaMessageHeaders.getArtifactId(props);
        String version = SchemaMessageHeaders.getVersion(props);
        if (groupId == null || artifactId == null) {
            throw new SchemaNotFoundException("missing X-Schema-* headers");
        }
        return schemaResolver.resolveByCoordinates(new SchemaCoordinates(groupId, artifactId, version));
    }

    private SerializationStrategy strategyFor(SchemaType type) {
        SerializationStrategy s = strategies.get(type);
        if (s == null) throw new MessageConversionException("No SerializationStrategy for " + type);
        return s;
    }

    private static void ensureMessageId(MessageProperties props) {
        if (props.getHeader(SchemaMessageHeaders.MESSAGE_ID) == null) {
            props.setHeader(SchemaMessageHeaders.MESSAGE_ID, UUID.randomUUID().toString());
        }
        if (props.getHeader(SchemaMessageHeaders.CORRELATION_ID) == null) {
            // Propagate OTel trace ID as correlation ID so produce→consume spans are linkable.
            // Falls back to a random UUID when no active trace context exists.
            String traceId = org.slf4j.MDC.get("traceId");
            props.setHeader(SchemaMessageHeaders.CORRELATION_ID,
                    traceId != null ? traceId : UUID.randomUUID().toString());
        }
    }
}
