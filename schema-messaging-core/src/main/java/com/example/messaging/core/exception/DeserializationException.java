package com.example.messaging.core.exception;

/**
 * Message bytes cannot be parsed into the target type (spec App. B).
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class DeserializationException extends SchemaMessagingException implements PermanentFailure {

    public DeserializationException(String context, Throwable cause) {
        super("Deserialization failed: " + context, context, cause);
    }

    public DeserializationException(String context, String detail) {
        super("Deserialization failed [" + context + "]: " + detail, context);
    }
}
