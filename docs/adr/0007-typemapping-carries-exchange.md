# ADR-0007: TypeMapping Carries the AMQP Exchange

## Status

Accepted. Amends [ADR-0006](0006-typemapping-relocated-to-event-contract-kit.md), which scoped
`TypeMapping` to exactly four fields — this decision adds a fifth.

## Context

`EventPublisher.publish(String exchange, Object event)` already looked up the event's
`TypeMapping` via `TypeMappingRegistry` to read `routingKey()` — the exchange was the one piece of
AMQP routing metadata still supplied by the caller at every call site (`OrderEventRouting.EXCHANGE`
/ `CustomerEventRouting.EXCHANGE`, one constant per domain, referenced directly by
`OrderController`/`CustomerController` and the round-trip integration tests).

The goal (spec `simplified-publish-and-listen.md`) is `publish(Object event)` — no exchange
argument at all. That requires `EventPublisher` to resolve the exchange internally, and
`TypeMapping` is where every other piece of per-event routing metadata already lives.

Three options were considered:
1. **Add an `exchange` field to `TypeMapping`.**
2. **Derive it from `coordinates().groupId()`** via a naming convention (`groupId + ".exchange"`,
   e.g. `"events.orders"` → `"events.orders.exchange"` — the two strings do already match today).
3. **A separate exchange registry**, independent of `TypeMapping`.

## Decision

Add a fifth field, `exchange`, to `TypeMapping`:

```java
public record TypeMapping(
        Class<?> javaType,
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        String routingKey,
        String exchange
) {}
```

Populated explicitly in `OrderTypeMappingAutoConfiguration`/`CustomerTypeMappingAutoConfiguration`
from the existing `OrderEventRouting.EXCHANGE`/`CustomerEventRouting.EXCHANGE` constants — no new
constants, no new source of truth for the exchange name itself.

Option 2 (groupId-suffix convention) was rejected: it relies on an implicit string-suffix
convention enforced nowhere in code, and would silently break the day an exchange name is chosen
that doesn't follow the pattern. Option 3 (separate registry) was rejected: it introduces a second
parallel lookup concept for data that is exactly the kind of per-event routing metadata
`TypeMapping` already exists to carry (see `routingKey`).

## Consequences

- `EventPublisher.publish` collapses to a single `publish(Object event)` overload — both exchange
  and routing key are read from the same `TypeMappingRegistry.findByJavaType` lookup that already
  existed. The two-argument overload is removed outright (no back-compat overload kept).
- `TypeMapping` is a shared type in `event-contract-kit`, depended on by both `schema-messaging-core`
  and both `*-contracts` modules — this change is visible everywhere the record is constructed.
  Every existing `new TypeMapping(...)` call site (production code and tests) needed a fifth
  argument; the field was appended, not inserted, to keep those calls additive rather than
  requiring argument reordering.
- Amends `spec/contract-owned-amqp-topology.md` Guiding Principle 3, which stated the hand-written
  `TypeMapping` beans "stay exactly as they are — a separate concern from physical AMQP topology."
  That is no longer true: `TypeMapping` now also carries one piece of physical topology data
  (exchange), closing the gap between schema-resolution wiring and physical topology for the field
  that was still caller-supplied.
