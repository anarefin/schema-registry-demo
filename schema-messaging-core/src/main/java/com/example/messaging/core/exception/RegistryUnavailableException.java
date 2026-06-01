package com.example.messaging.core.exception;

/**
 * Apicurio Registry cannot be reached (spec App. B).
 * TRANSIENT failure — eligible for retry. Stale cache is served when available.
 */
public class RegistryUnavailableException extends SchemaMessagingException {

    public RegistryUnavailableException(String context, Throwable cause) {
        super("Registry unavailable: " + context, context, cause);
    }

    public RegistryUnavailableException(String context) {
        super("Registry unavailable: " + context, context);
    }
}
