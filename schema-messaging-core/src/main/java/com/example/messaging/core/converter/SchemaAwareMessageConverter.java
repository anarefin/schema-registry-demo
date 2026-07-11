package com.example.messaging.core.converter;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.IncompatibleSchemaTypeException;
import com.example.messaging.core.exception.MissingSchemaHeadersException;
import com.example.messaging.core.exception.SchemaMessagingException;
import com.example.messaging.core.exception.UnknownSchemaArtifactException;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.messaging.core.schema.LocalSchemaCatalog;
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
 * <p><b>Produce ({@link #toMessage}):</b> lookup TypeMapping → look up schema in the
 * {@link LocalSchemaCatalog} → validate + serialize → populate {@code X-Schema-*} headers +
 * content-type. Validation failure throws
 * {@link com.example.messaging.core.exception.SchemaValidationException} and NO message is
 * emitted.
 *
 * <p><b>Consume ({@link #fromMessage}):</b> read {@code X-Schema-*} headers → look up
 * TypeMapping by group/artifact → dispatch to matching strategy → return typed object.
 */
public class SchemaAwareMessageConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(SchemaAwareMessageConverter.class);

    private final TypeMappingRegistry typeMappingRegistry;
    private final LocalSchemaCatalog localSchemaCatalog;
    private final Map<SchemaType, SerializationStrategy> strategies;

    public SchemaAwareMessageConverter(
            TypeMappingRegistry typeMappingRegistry,
            LocalSchemaCatalog localSchemaCatalog,
            List<SerializationStrategy> strategies) {
        this.typeMappingRegistry = typeMappingRegistry;
        this.localSchemaCatalog = localSchemaCatalog;
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(SerializationStrategy::schemaType, Function.identity()));
        // Fail fast: every registered TypeMapping must have a matching strategy available, and
        // that strategy must be able to eagerly prepare (e.g. compile) its schema content —
        // catching a missing or malformed schema at startup rather than on the first message.
        typeMappingRegistry.all().forEach(mapping -> {
            SerializationStrategy strategy = this.strategies.get(mapping.schemaType());
            if (strategy == null) {
                throw new IllegalStateException(
                        "No SerializationStrategy registered for SchemaType." + mapping.schemaType()
                        + " (required by TypeMapping for " + mapping.javaType().getName() + ")");
            }
            strategy.warm(localSchemaCatalog.get(mapping.coordinates()));
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

        ResolvedSchema schema = localSchemaCatalog.get(mapping.coordinates());
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
        SchemaMessageHeaders.setSchemaHeaders(messageProperties, mapping.coordinates(),
                mapping.schemaType(), strategy.contentType());

        log.info("Serialized {} to {} bytes [coordinates={}, routingKey={}]",
                type.getSimpleName(), bytes.length, mapping.coordinates(), mapping.routingKey());
        return new Message(bytes, messageProperties);
    }

    // ---- consume path -----------------------------------------------------

    @Override
    public Object fromMessage(Message message) throws MessageConversionException {
        MessageProperties props = message.getMessageProperties();

        String groupId = SchemaMessageHeaders.getGroupId(props);
        String artifactId = SchemaMessageHeaders.getArtifactId(props);
        String headerTypeName = SchemaMessageHeaders.getSchemaTypeName(props);

        requireHeader(groupId, SchemaMessageHeaders.GROUP_ID);
        requireHeader(artifactId, SchemaMessageHeaders.ARTIFACT_ID);
        requireHeader(headerTypeName, SchemaMessageHeaders.TYPE);

        // Validate type against registered TypeMapping (TC-1.11). Compare the raw header
        // string so unsupported types (e.g. a stale producer sending PROTOBUF) surface as a
        // crisp IncompatibleSchemaTypeException rather than a downstream validation error.
        TypeMapping mapping = typeMappingRegistry
                .findByGroupAndArtifact(groupId, artifactId)
                .orElseThrow(() -> new UnknownSchemaArtifactException(groupId, artifactId));

        if (!headerTypeName.equalsIgnoreCase(mapping.schemaType().name())) {
            throw new IncompatibleSchemaTypeException(
                    mapping.schemaType().name(), headerTypeName,
                    groupId + ":" + artifactId);
        }

        ResolvedSchema schema = localSchemaCatalog.get(mapping.coordinates());
        SerializationStrategy strategy = strategyFor(mapping.schemaType());

        byte[] body = message.getBody();
        try {
            Object result = strategy.deserialize(body, mapping.javaType(), schema);
            log.debug("Deserialized {} bytes → {} [coordinates={}]",
                    body.length, mapping.javaType().getSimpleName(), mapping.coordinates());
            return result;
        } catch (SchemaMessagingException e) {
            throw e;
        } catch (Exception e) {
            throw new DeserializationException(mapping.javaType().getSimpleName(), e);
        }
    }

    private static void requireHeader(String value, String headerName) {
        if (value == null || value.isBlank()) {
            throw new MissingSchemaHeadersException(headerName + " is required");
        }
    }

    // ---- private helpers --------------------------------------------------

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
