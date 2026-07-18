package com.example.amqp.topology.mapping;

/**
 * Thrown when runtime {@link TypeMapping} registration cannot proceed: a malformed/unreadable
 * {@code META-INF/event-mappings.idx} resource, an indexed class that is missing or no longer
 * carries {@link EventMapping} (annotation/index mismatch), two indexed events colliding on the
 * same {@link SchemaCoordinates}, or a {@link RegisterEventMappings} import whose package matched
 * no indexed event. Unchecked so it aborts context refresh and fails startup fast — a broken index
 * is never served silently (mirrors {@code LocalSchemaCatalog}'s fail-fast contract).
 */
public class EventMappingRegistrationException extends RuntimeException {

    public EventMappingRegistrationException(String message) {
        super(message);
    }

    public EventMappingRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
