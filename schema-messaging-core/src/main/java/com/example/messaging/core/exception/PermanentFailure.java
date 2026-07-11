package com.example.messaging.core.exception;

/**
 * Marker for exceptions that {@link com.example.messaging.core.consumer.EventConsumerSupport#classify}
 * routes straight to the DLQ, no retry. Implementing this interface is how a new exception type
 * self-classifies as permanent, instead of requiring a second edit to a hand-maintained set.
 */
public interface PermanentFailure {
}
