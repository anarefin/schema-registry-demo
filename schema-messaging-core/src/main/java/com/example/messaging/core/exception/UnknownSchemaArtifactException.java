package com.example.messaging.core.exception;

/**
 * No {@code TypeMapping} is registered for the inbound group/artifact coordinates.
 * PERMANENT failure — routes straight to DLQ, no retry.
 */
public class UnknownSchemaArtifactException extends SchemaMessagingException {

    public UnknownSchemaArtifactException(String groupId, String artifactId) {
        super("No TypeMapping for artifact " + groupId + ":" + artifactId,
                groupId + ":" + artifactId);
    }
}
