# ADR-0005: Contracts May Depend on Core (One-Directional)

## Status

Accepted. **Superseded by [ADR-0006](0006-typemapping-relocated-to-event-contract-kit.md):** the
`contracts → core` dependency edge this ADR opened has been closed again — `TypeMapping`,
`SchemaCoordinates`, and `SchemaType` were relocated out of `schema-messaging-core` into
`event-contract-kit` (the leaf module contracts already depended on), so contracts no longer needs
core at all. The problem this ADR solved (duplicated `TypeMapping` wiring across
producer-service/consumer-service) remains solved — the auto-configuration classes it introduced
are unaffected — only the dependency direction used to reach the shared types changed. Kept below
as the historical record of why the one-directional exception was granted in the first place.

## Context

ADR-0002 / `spec/contract-owned-amqp-topology.md` Guiding Principle 2 banned any
`contracts ↔ core` dependency, in either direction, machine-enforced via `maven-enforcer-plugin`
on all three modules. The architecture review (`spec/architecture-review.html`, Candidate 1)
found that eliminating the four duplicated `TypeMapping` configuration classes in
producer-service/consumer-service requires `order-contracts`/`customer-contracts` to reference
`TypeMapping`, `SchemaCoordinates`, and `SchemaType` from `schema-messaging-core` — which the
bidirectional ban explicitly forbids.

## Decision

Amend Principle 2 to one-directional: contracts may depend on `schema-messaging-core`; core must
still never depend on any `*-contracts` module (unchanged, still enforced via
`bannedDependencies` in `schema-messaging-core/pom.xml`). The `bannedDependencies` exclude for
`schema-messaging-core` is removed from `order-contracts/pom.xml` and `customer-contracts/pom.xml`.

## Consequences

Every future `*-contracts` module gains the *option* (not obligation) to depend on all of
`schema-messaging-core`, not just the `TypeMapping` package — Maven dependency granularity is
per-module. This trades the fully-symmetric isolation of contracts and core for eliminating
duplicated `TypeMapping` wiring across services. `core ↛ contracts` remains true and enforced;
only the reverse direction opened.
