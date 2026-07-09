# ADR-0002: Contract-Owned, Domain-Scoped AMQP Topology

## Status

Accepted. See `spec/contract-owned-amqp-topology.md` for the full specification.

## Context

[ADR-0001](0001-code-first-schema-generation.md) and `spec/code-first-schema.md` (Guiding
Principles 4–5, design D3) established contracts as **transport-agnostic**: no Spring/AMQP on
their classpath, with RabbitMQ topology (exchanges, queues, DLQs, retry ladder) generated once in
`schema-messaging-core` and driven by iterating the `TypeMappingRegistry`.

That model assumed a single shared exchange set across every domain. As the project's direction
shifted toward each domain (orders, customers) owning its full transport story — not just its
schema and routing keys — the shared-exchange model stopped fitting: a domain-scoped exchange
naming convention (mirroring the Apicurio registry groups `events.orders` / `events.customers`)
requires the exchange *declarations* themselves to live per-domain, not in one generic,
domain-blind auto-configuration.

This is a hard-to-reverse, deliberate reversal of an existing explicit spec, made with the
tradeoff fully understood: it trades centralized genericity (one place declares topology for
every event) for per-domain ownership (each contract is self-sufficient to wire into a
producer/consumer service as a plain Maven dependency, with no hand-written per-service topology
glue).

## Decision

Reverse Guiding Principles 4 and 5 and design D3 of `spec/code-first-schema.md`:

- **Contracts own their transport.** `order-contracts` and `customer-contracts` each declare their
  own domain's exchanges, queues, DLQs, and retry ladder via a self-activating Spring Boot
  `@AutoConfiguration` (`OrderTopologyAutoConfiguration` / `CustomerTopologyAutoConfiguration`),
  shipped in the contract jar. Contracts gain `spring-rabbit` + `spring-boot-autoconfigure` on
  their classpath — no longer transport-agnostic. Schema-generation purity (victools, code-first
  records) is unaffected.
- **Exchanges are domain-scoped**: `events.orders.{exchange,dlx,retry.exchange}` and
  `events.customers.{exchange,dlx,retry.exchange}`, replacing the single shared `events.exchange`
  / `events.dlx` / `events.retry.exchange` set. Routing keys and queue/DLQ names are unchanged.
- **Reusable topology-building logic moves to a new sibling module**, `amqp-topology-kit` — not
  into core, not duplicated per contract — so the retry-ladder TTL policy (5s/30s/5m) stays
  defined in exactly one place even though its declaration is now per-domain. `core ↛ contracts`
  stays enforced, and neither `schema-messaging-core` nor `amqp-topology-kit` may depend on the
  other or on any `*-contracts` module — machine-enforced via `maven-enforcer-plugin`
  `bannedDependencies` on all three modules.
- **`DlxMessageRecoverer`** (the one shared bean used by every domain's `@RabbitListener`s) can no
  longer assume a fixed exchange name. It derives the DLX/retry exchange per message from
  `MessageProperties.getReceivedExchange()` (correct even for messages that already traversed the
  retry ladder, since retry queues dead-letter back to the main exchange before re-delivery) —
  pure string manipulation on data already present on the message, requiring no new dependency for
  core.
- This is a one-shot, clean cutover — a POC with no live production traffic and only two domains —
  not a phased or coexisting rollout.

## Consequences

- A contract module is now a complete, self-sufficient transport unit: dropping
  `order-contracts` into a service's classpath wires its full AMQP topology automatically, with no
  per-service topology glue beyond the existing `TypeMapping` beans (schema-resolution wiring is
  untouched by this decision).
- Adding a third domain means adding a third `*-contracts` module with its own topology
  auto-configuration — not touching a shared, domain-blind class in core.
- The retry policy itself (5s/30s/5m, max 3 retries) is unchanged; only *where* the
  topology-declaring code lives changed.
- `schema-messaging-core` is smaller and more clearly scoped to schema resolution, message
  conversion, and failure-routing decisions — it no longer knows about AMQP exchange topology at
  all.
- `spec/code-first-schema.md` Guiding Principles 4–5 and D3 remain in the document as historical
  record (cross-referenced to this ADR and to `spec/contract-owned-amqp-topology.md`) rather than
  being deleted, since they document a real, deliberate design that was later reversed.
