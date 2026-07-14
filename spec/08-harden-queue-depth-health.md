# 08 — Harden `QueueDepthHealthIndicator`

**Severity:** Medium · **Findings:** SCAL-002 + SCAL-003 · **Type:** AFK

## What to build

The consumer `QueueDepthHealthIndicator` has two production hazards:

1. **DLQ backlog flips health to DOWN.** `health()` returns `Health.down()` whenever any DLQ has
   messages. As a `HealthIndicator` it contributes to the composite `/actuator/health`; wired to a
   Kubernetes **liveness** probe a DLQ backlog would restart the pod — killing the very consumer
   needed to drain it — and to a **readiness** probe it would pull the pod from rotation. A data
   condition (poison messages) should not take the compute out of service.
2. **Per-probe broker RPCs.** Each `health()` call does two `rabbitAdmin.getQueueInfo(...)` round
   trips (main + DLQ) per handled event type. Under aggressive probe intervals × many event types ×
   many replicas this adds real management-channel load, and each probe blocks on the broker — slow
   health checks exactly when the system is stressed.

Keep DLQ depth as reported detail/metric and alert on it, reserve DOWN for the consumer's own
inability to function (broker unreachable — already modelled as UNKNOWN on probe failure), assign the
indicator to a non-liveness health group, and cache/sample the broker queries with a short TTL.

## Acceptance criteria

- [ ] DLQ backlog no longer flips the indicator to DOWN; depth is surfaced as detail/metric.
- [ ] DOWN reserved for broker-unreachable (compute-can't-function) conditions.
- [ ] Indicator assigned to a non-liveness health group (won't restart/de-rotate pods on data conditions).
- [ ] Broker queue-depth queries cached with a short TTL (or sampled on a schedule) instead of per-probe.
- [ ] A probe with a non-empty DLQ but a healthy broker does not report DOWN (test coverage).

## Blocked by

None — can start immediately.
