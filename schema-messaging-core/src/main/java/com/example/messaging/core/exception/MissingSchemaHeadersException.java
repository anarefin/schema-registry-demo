package com.example.messaging.core.exception;

/**
 * Required {@code X-Schema-*} headers are missing or blank on an inbound message.
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class MissingSchemaHeadersException extends SchemaMessagingException {

    public MissingSchemaHeadersException(String detail) {
        super("Missing required schema headers: " + detail, detail);
    }
}
