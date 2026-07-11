package com.example.messaging.core.exception;

/**
 * X-Schema-Type header does not match the registered type for the artifact (spec App. B).
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class IncompatibleSchemaTypeException extends SchemaMessagingException implements PermanentFailure {

    private final String expectedType;
    private final String actualType;

    public IncompatibleSchemaTypeException(String expected, String actual, String coordinates) {
        super("Schema type mismatch for " + coordinates + ": expected=" + expected + ", actual=" + actual, coordinates);
        this.expectedType = expected;
        this.actualType = actual;
    }

    /** The schema type the registered TypeMapping expects. */
    public String expectedType() { return expectedType; }

    /** The schema type declared in the incoming message header. */
    public String actualType() { return actualType; }
}
