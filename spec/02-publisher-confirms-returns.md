# 02 — RabbitMQ publisher confirms/returns

**Severity:** High · **Finding:** SPRING-001 · **Type:** AFK

## What to build

`EventPublisher.publish` uses fire-and-forget `rabbitTemplate.send(exchange, routingKey, message)`.
No publisher confirms, no returns, no `mandatory` flag, and no confirm/return callbacks are
configured anywhere. A broker nack, a connection drop mid-publish, or an unroutable message (e.g. a
routing-key/exchange mismatch after a topology change) is dropped with no error surfaced — the
controller still returns `201 Created`. For a system whose value proposition is *governed, reliable*
eventing, this is the sharpest production gap.

Enable correlated publisher confirms + returns + mandatory, and register confirm/return callbacks
so a nack or return becomes a surfaced failed publish (5xx + retry/outbox) instead of a silent drop.

## Acceptance criteria

- [ ] `spring.rabbitmq.publisher-confirm-type=correlated` and `publisher-returns=true` configured.
- [ ] `RabbitTemplate.setMandatory(true)` enabled.
- [ ] Confirm and return callbacks registered; a nack/return is logged and surfaced (not swallowed).
- [ ] An unroutable publish results in a failed publish signalled to the caller (not a `201`).
- [ ] Test coverage for the nack/return path.

## Blocked by

None — can start immediately.
