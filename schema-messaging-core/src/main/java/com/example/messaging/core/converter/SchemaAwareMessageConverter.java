package com.example.messaging.core.converter;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.SchemaMessagingException;
import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
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

    public SchemaAwareMessageConverter(
            TypeMappingRegistry typeMappingRegistry,
            SchemaResolver schemaResolver,
            List<SerializationStrategy> strategies) {
        this.typeMappingRegistry = typeMappingRegistry;
        this.schemaResolver = schemaResolver;
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(SerializationStrategy::schemaType, Function.identity()));
        // Fail fast: every registered TypeMapping must have a matching strategy available.
        typeMappingRegistry.all().forEach(mapping -> {
            if (!this.strategies.containsKey(mapping.schemaType())) {
                throw new IllegalStateException(
                        "No SerializationStrategy registered for SchemaType." + mapping.schemaType()
                        + " (required by TypeMapping for " + mapping.javaType().getName() + ")");
            }
        });
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
        } catch (SchemaMessagingException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageConversionException("Serialization failed for " + type.getName(), e);
        }

        ensureCorrelationId(messageProperties);
        SchemaMessageHeaders.setSchemaHeaders(messageProperties, schema.globalId(), mapping.coordinates(),
                mapping.schemaType(), strategy.contentType());

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
        String headerTypeName = SchemaMessageHeaders.getSchemaTypeName(props);

        // Validate type against registered TypeMapping (TC-1.11). Compare the raw header
        // string so unsupported types (e.g. a stale producer sending PROTOBUF) surface as a
        // crisp IncompatibleSchemaTypeException rather than a downstream validation error.
        TypeMapping mapping = typeMappingRegistry
                .findByGroupAndArtifact(groupId, artifactId)
                .orElseThrow(() -> new MessageConversionException(
                        "No TypeMapping for artifact " + groupId + ":" + artifactId));

        if (headerTypeName != null && !headerTypeName.equalsIgnoreCase(mapping.schemaType().name())) {
            throw new IncompatibleSchemaTypeException(
                    mapping.schemaType().name(), headerTypeName,
                    groupId + ":" + artifactId);
        }

        SchemaType effectiveType = mapping.schemaType();
        ResolvedSchema schema = resolveSchema(props, effectiveType);
        SerializationStrategy strategy = strategyFor(effectiveType);

        byte[] body = message.getBody();
        try {
            Object result = strategy.deserialize(body, mapping.javaType(), schema);
            log.debug("Deserialized {} bytes → {} [globalId={}]",
                    body.length, mapping.javaType().getSimpleName(), schema.globalId());
            return result;
        } catch (SchemaMessagingException e) {
            throw e;
        } catch (Exception e) {
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

    private static void ensureCorrelationId(MessageProperties props) {
        if (props.getHeader(SchemaMessageHeaders.CORRELATION_ID) == null) {
            props.setHeader(SchemaMessageHeaders.CORRELATION_ID, UUID.randomUUID().toString());
        }
    }
}
