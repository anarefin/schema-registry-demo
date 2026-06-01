package com.example.messaging.core.exception;

/**
 * X-Schema-Type header does not match the registered type for the artifact (spec App. B).
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class IncompatibleSchemaTypeException extends SchemaMessagingException {

    public IncompatibleSchemaTypeException(String expected, String actual, String coordinates) {
        super("Schema type mismatch for " + coordinates + ": expected=" + expected + ", actual=" + actual, coordinates);
    }
}
