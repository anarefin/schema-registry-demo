package com.example.messaging.core.serde;

import com.example.messaging.core.exception.DeserializationException;
import com.example.messaging.core.exception.SchemaValidationException;
import com.example.messaging.core.exception.SerializationException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaType;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protobuf serialization strategy (spec §6).
 * Wire format: pure Protobuf bytes — NO magic byte, NO length prefix.
 * Deserialization uses reflection ({@code parseFrom(byte[])}) so the core stays domain-agnostic.
 *
 * <p>The {@code parseFrom} {@link Method} is cached per target type — repeated reflection
 * lookups on a hot path are expensive due to JVM synchronization on the method table.
 */
public class ProtobufStrategy implements SerializationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ProtobufStrategy.class);

    private final ConcurrentHashMap<Class<?>, Method> parseFromCache = new ConcurrentHashMap<>();

    @Override
    public SchemaType schemaType() {
        return SchemaType.PROTOBUF;
    }

    /**
     * No schema-level validation for Protobuf (the type system enforces structure).
     * Serializes to raw protobuf bytes.
     */
    @Override
    public byte[] serialize(Object payload, ResolvedSchema schema)
            throws SchemaValidationException, SerializationException {
        if (!(payload instanceof Message msg)) {
            throw new SerializationException(
                    payload.getClass().getName(),
                    "Payload is not a com.google.protobuf.Message");
        }
        try {
            return msg.toByteArray();
        } catch (Exception e) {
            throw new SerializationException(payload.getClass().getName(), e);
        }
    }

    /**
     * Parses raw bytes into an instance of {@code targetType} via the generated
     * {@code parseFrom(byte[])} static method. The resolved {@link Method} is cached
     * so reflection lookup happens only once per target type.
     */
    @Override
    public Object deserialize(byte[] bytes, Class<?> targetType, ResolvedSchema schema)
            throws DeserializationException {
        String ctx = targetType.getSimpleName();
        try {
            Method parseFrom = parseFromCache.computeIfAbsent(targetType, this::resolveParseFrom);
            return parseFrom.invoke(null, (Object) bytes);
        } catch (DeserializationException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Protobuf parseFrom failed for type={}", ctx, e);
            throw new DeserializationException(ctx, e);
        }
    }

    private Method resolveParseFrom(Class<?> targetType) {
        try {
            return targetType.getMethod("parseFrom", byte[].class);
        } catch (NoSuchMethodException e) {
            throw new DeserializationException(targetType.getSimpleName(), e);
        }
    }
}
