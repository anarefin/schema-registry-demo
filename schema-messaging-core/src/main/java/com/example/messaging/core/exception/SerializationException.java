package com.example.messaging.core.exception;

/**
 * Object cannot be serialized to the target format (spec App. B).
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class SerializationException extends SchemaMessagingException {

    public SerializationException(String context, Throwable cause) {
        super("Serialization failed: " + context, context, cause);
    }

    public SerializationException(String context, String detail) {
        super("Serialization failed [" + context + "]: " + detail, context);
    }
}
