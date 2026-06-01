package com.example.messaging.core.exception;

/**
 * Base for all schema-messaging exceptions (spec Appendix B).
 * Carries optional schema coordinates context for DLQ headers.
 */
public abstract class SchemaMessagingException extends RuntimeException {

    private final String context;

    protected SchemaMessagingException(String message, String context) {
        super(message);
        this.context = context;
    }

    protected SchemaMessagingException(String message, String context, Throwable cause) {
        super(message, cause);
        this.context = context;
    }

    public String context() {
        return context;
    }
}
