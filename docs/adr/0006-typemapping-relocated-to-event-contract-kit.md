# ADR-0006: TypeMapping/SchemaCoordinates/SchemaType Relocated to event-contract-kit

## Status

Accepted. Supersedes [ADR-0005](0005-contracts-may-depend-on-core.md).

## Context

ADR-0005's sole purpose was letting `order-contracts`/`customer-contracts` reach three small data
types — `TypeMapping`, `SchemaCoordinates`, `SchemaType` — so each domain could declare its own
`TypeMapping` `@Bean`s (`OrderTypeMappingAutoConfiguration`/`CustomerTypeMappingAutoConfiguration`)
instead of duplicating that wiring in producer-service/consumer-service. Those three types lived in
`schema-messaging-core`, so reaching them required opening a `contracts → core` dependency,
amending the original bidirectional isolation rule (`spec/contract-owned-amqp-topology.md`
Guiding Principle 2) to one-directional.

The downside, noted in ADR-0005 itself: Maven dependency granularity is per-module, so contracts
modules gained the *option* to depend on all of `schema-messaging-core` — Spring AMQP,
`json-schema-validator`, `EventPublisher`, `LocalSchemaCatalog`, the exception hierarchy, etc. —
just to reach 3 records/an enum.

Both `order-contracts` and `customer-contracts` already depended on `amqp-topology-kit` (topology
naming conventions + retry-ladder factory), a pure leaf module with zero dependency on core. That
made a narrower fix available: move the 3 types into that leaf module instead of opening a path
into core.

## Decision

- Relocate `TypeMapping`, `SchemaCoordinates`, `SchemaType` out of `schema-messaging-core` into
  the renamed `event-contract-kit` module (formerly `amqp-topology-kit`), package
  `com.example.amqp.topology.mapping`.
- `schema-messaging-core` gains a new compile-scope dependency on `event-contract-kit`; the
  enforcer ban on that edge is removed. `TypeMappingRegistry`, `ResolvedSchema`, and the rest of
  core's registry/converter/catalog logic stay in core — only the 3 shared data types moved.
- `order-contracts`/`customer-contracts` drop their dependency on `schema-messaging-core`
  entirely. The pre-ADR-0005 `ban-core-module` enforcer rule is reinstated in both contracts
  POMs, restoring `spec/contract-owned-amqp-topology.md` Guiding Principle 2's original
  bidirectional isolation — `contracts ↔ core` is zero dependency in either direction again,
  achieved without reintroducing the duplicated `TypeMapping` wiring ADR-0005 eliminated.
- `amqp-topology-kit` is renamed to `event-contract-kit` (module directory, Maven artifactId,
  and docs only) because it now carries two different concerns — AMQP topology conventions and
  event-contract runtime metadata — and the old name no longer described its contents. The
  existing Java package `com.example.amqp.topology.*` for the pre-existing topology classes is
  left unchanged to avoid unrelated import churn; this leaves an accepted cosmetic mismatch
  (module `event-contract-kit` contains a `com.example.amqp.topology` package, including the new
  `com.example.amqp.topology.mapping` sub-package for the relocated types).

## Consequences

- `contracts ↔ core` returns to zero dependency in either direction, machine-enforced on both
  sides (`schema-messaging-core`'s enforcer still bans `order-contracts`/`customer-contracts`;
  both contracts POMs now ban `schema-messaging-core`).
- `event-contract-kit` remains a pure leaf module itself (still banned from depending on core or
  either `*-contracts` module) but is now depended on by three modules instead of two:
  `order-contracts`, `customer-contracts`, and `schema-messaging-core`.
- Contracts modules no longer pull in Spring AMQP's messaging internals, `json-schema-validator`,
  or core's exception hierarchy merely to declare `TypeMapping` beans — their compile classpath
  now only grows by 3 small types via a module they already depended on.
- The module/package name mismatch noted above is accepted as-is for this POC rather than also
  renaming `com.example.amqp.topology.*` to something scope-neutral, which would touch every
  existing topology class's callers for no functional gain.
