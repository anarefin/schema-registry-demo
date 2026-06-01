package com.example.messaging.core.consumer;

/**
 * Failure routing classification for dead-letter handling (spec §9/§11).
 *
 * <ul>
 *   <li>{@link #RETRY} — transient failure; route through retry exchange with TTL ladder.</li>
 *   <li>{@link #DLQ_DIRECT} — permanent failure; route straight to DLQ, no retry.</li>
 * </ul>
 */
public enum RoutingDecision {
    RETRY,
    DLQ_DIRECT
}
