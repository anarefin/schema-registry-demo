# Specification: Contract-Owned, Domain-Scoped AMQP Topology

## Purpose

Move ownership of RabbitMQ topology (exchanges, queues, DLQs, retry ladder) out of
`schema-messaging-core` and into each `*-contracts` module, so that a contract module is
self-sufficient to wire into a producer/consumer service as a plain Maven dependency — no
hand-written per-service topology glue needed beyond what already exists for schema resolution
(`TypeMapping` beans, unchanged).

This **supersedes Guiding Principle 4 and 5, and design section D3, of
`spec/code-first-schema.md`**. That spec established contracts as transport-agnostic — "no
Spring/AMQP on their classpath" — with topology "generated once in core and driven by contract
metadata, never hand-authored per domain." This change deliberately reverses that: each domain now
owns its full transport story, including domain-scoped exchanges, because a single generic
shared-exchange model no longer fits the direction the project is taking. `spec/code-first-schema.md`
should be read historically for D1/D2/D4/D5/D6 (schema generation, governance, CI) — only its
topology-ownership stance is superseded here.

This is a one-shot, clean cutover: a POC with no live production traffic and only two domains
(orders, customers) — no coexistence period between the old and new topology mechanisms.

## Guiding Principles (supersedes code-first-schema.md Principles 4 & 5)

> **Principle 2 is superseded, twice.** [ADR-0005](../docs/adr/0005-contracts-may-depend-on-core.md)
> first amended the bidirectional `contracts ↔ core` ban to one-directional, letting contracts
> depend on `schema-messaging-core` so each `*-contracts` module could register its own
> `TypeMapping` beans. [ADR-0006](../docs/adr/0006-typemapping-relocated-to-event-contract-kit.md)
> then closed that edge again by relocating `TypeMapping`/`SchemaCoordinates`/`SchemaType` out of
> core into the renamed `event-contract-kit` module (formerly `amqp-topology-kit`) — so
> `contracts ↔ core` is back to zero dependency in either direction, machine-enforced on both
> sides again, exactly as this principle originally intended. The *other* clause below (core does
> not depend on the shared kit) is now the part that no longer holds: `schema-messaging-core`
> depends on `event-contract-kit` as of ADR-0006. Retained below as historical record; see
> ADR-0006 for the current rule.

1. **Contracts own their transport.** Each `*-contracts` module declares its own domain's
   exchanges, queues, DLQs, and retry ladder directly via Spring AMQP, self-activating through a
   Spring Boot auto-configuration shipped in the jar. Contracts are no longer transport-agnostic —
   they gain `spring-rabbit` + `spring-boot-autoconfigure` on their classpath. (Schema generation
   purity — no victools, code-first records — is unaffected; see `code-first-schema.md` D1/D2.)
2. ~~**No contracts ↔ core dependency in either direction**, and core does not depend on the new
   shared kit either. `schema-messaging-core` must not depend on any `*-contracts` module (existing
   enforcer rule, unchanged) or on the new `amqp-topology-kit` module (new enforcer rule). Only
   contracts depend on the kit.~~ **Superseded, twice** — see note above. As of ADR-0006: the
   `contracts ↔ core` half is restored (true again, both directions enforced); the
   core-does-not-depend-on-the-kit half is now false (core depends on `event-contract-kit`,
   formerly `amqp-topology-kit`).
3. **Schema-resolution wiring is untouched.** The hand-written `TypeMapping` beans in
   `producer-service`/`consumer-service`'s `*ContractsConfiguration` classes (Java type ↔ registry
   coordinates ↔ routing key, used by `SchemaAwareMessageConverter`/`LocalSchemaCatalog`) stay exactly
   as they are — a separate concern from physical AMQP topology.
4. **Exchanges are domain-scoped**, named to mirror the Apicurio registry group (`events.orders`,
   `events.customers`), not a single shared exchange set for every event across every domain.
   Routing keys and queue/DLQ names are unchanged.
5. **Reusable, domain-agnostic topology-building logic lives in a new sibling module**
   (`amqp-topology-kit`) — not in core, not duplicated per contract — so the retry-ladder TTL
   policy (5s/30s/5m) stays defined in exactly one place even though its *declaration* is now
   per-domain.
6. **Consumer-side failure routing must not assume a global exchange.** The shared
   `DlxMessageRecoverer`/`rabbitListenerContainerFactory` (one bean, used by every domain's
   listeners) must resolve the DLX/retry exchange per message rather than from a fixed
   configuration value.

---

## Scope

### In scope
- New module `amqp-topology-kit`: domain-agnostic retry-ladder builder + naming-convention
  constants, depended on only by `order-contracts` and `customer-contracts`.
- Per-contract Spring Boot auto-configuration declaring that domain's exchanges/queues/DLQs/retry
  ladder (`OrderTopologyAutoConfiguration`, `CustomerTopologyAutoConfiguration`).
- Deletion of `EventExchanges`, `RetryTopologyFactory`, `EventTopologyAutoConfiguration` from
  `schema-messaging-core`.
- Fix to `DlxMessageRecoverer` so it derives the correct per-domain DLX/retry exchange from the
  message's actual received exchange, with no new dependency for core.
- Fix to `QueueDepthHealthIndicator` (consumer-service) to stop importing the now-deleted core
  class.
- New/extended `maven-enforcer-plugin` rules machine-enforcing the module-boundary rules above.
- Updates to `CONTEXT.md`, `CLAUDE.md`, `README.md`, `docs/TESTING-GUIDE.md`, and
  `spec/code-first-schema.md` to reflect the new topology ownership.
- A new ADR recording this reversal.

### Out of scope / deferred
- Any change to the retry policy itself (5s/30s/5m, max 3 retries) — only *where* the
  topology-declaring code lives changes, not the failure model (`code-first-schema.md` /
  `CLAUDE.md` failure-model section stays behaviorally accurate).
- Any change to `TypeMapping`/`TypeMappingRegistry`/schema-resolution wiring.
- Any change to schema generation, governance, or CI (`code-first-schema.md` D1/D2/D4/D5).
- A coexistence/phased rollout — explicitly rejected in favor of a one-shot cutover.

---

## Design

### D1 — `amqp-topology-kit` (new sibling module)

Registered in root `<modules>`. Depends only on `spring-rabbit` (version from the already-imported
Spring Boot BOM) + `spring-boot-starter-test` at test scope. Package `com.example.amqp.topology`.
Carries its own `maven-enforcer-plugin` `bannedDependencies` rule excluding
`com.example:schema-messaging-core`, `com.example:order-contracts`, `com.example:customer-contracts`
— it stays a pure leaf module.

- **`TopologyNaming`** (final, all-static): `QUEUE_SUFFIX = ".queue"`, `DLQ_SUFFIX = ".dlq"`,
  `queueName(rk)`, `dlqName(rk)`, `retryRoutingKey(rk, tier)`, `tierSuffix(tier)` (0→"5s",
  1→"30s", 2→"5m").
- **`EventTopologyFactory`**: `retryQueue(baseRoutingKey, tier, ttlMs, mainExchangeName)`,
  `retryBinding(queue, retryExchange, baseRoutingKey, tier)`, and
  `declarablesForEvent(routingKey, TopicExchange mainExchange, TopicExchange dlx,
  TopicExchange retryExchange, long[] tierTtlsMs)` → the full `List<Declarable>` (main
  queue+binding, DLQ+binding, 3 retry queues+bindings) for one event. This generalizes the old
  `RetryTopologyFactory` by taking exchange names as parameters instead of hardcoding
  `events.exchange`.
- `TopologyNamingTest` / `EventTopologyFactoryTest` (Surefire) asserting the naming conventions.

### D2 — Per-contract topology auto-configuration

For both `order-contracts` and `customer-contracts` (order-contracts shown; mirror for customers):

- `OrderEventRouting` gains: `EXCHANGE = "events.orders.exchange"`, `DLX = "events.orders.dlx"`,
  `RETRY_EXCHANGE = "events.orders.retry.exchange"`, plus bean-name constants (`BEAN_EXCHANGE`,
  `BEAN_DLX`, `BEAN_RETRY_EXCHANGE`). Existing routing-key/queue/DLQ constants are unchanged.
- New `com.example.contracts.orders.topology.OrderTopologyAutoConfiguration`
  (`@AutoConfiguration`, self-contained — no `@ConditionalOnBean`/ordering constraint, since it
  enumerates its own 3 routing keys directly rather than iterating a registry it can't see):
  declares the 3 domain `TopicExchange` beans (each `@ConditionalOnMissingBean`), reads the same
  `events.retry.tier{0,1,2}.ms` properties (defaults 5000/30000/300000), and one `Declarables`
  bean built by looping `EventTopologyFactory.declarablesForEvent(...)` over
  `CREATED_ROUTING_KEY`/`SHIPPED_ROUTING_KEY`/`CANCELLED_ROUTING_KEY`.
- New `order-contracts/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  containing `com.example.contracts.orders.topology.OrderTopologyAutoConfiguration`.
- `order-contracts/pom.xml` gains `spring-rabbit`, `spring-boot-autoconfigure`,
  `com.example:amqp-topology-kit` (compile), plus a new enforcer rule banning
  `com.example:schema-messaging-core`. Module-level Javadoc comment updated to drop the "no
  Spring/AMQP on the classpath" claim.

### D3 — Deletions and required fixes in `schema-messaging-core`

- Delete `.../amqp/EventExchanges.java`, `.../amqp/RetryTopologyFactory.java`,
  `.../amqp/EventTopologyAutoConfiguration.java`; remove the empty `amqp` package if nothing else
  remains; remove the `EventTopologyAutoConfiguration` line from core's
  `AutoConfiguration.imports`.
- **`DlxMessageRecoverer` fix:** drop the `dlxExchange`/`retryExchange` constructor params/fields;
  derive them per message from `MessageProperties.getReceivedExchange()` (correct even for
  messages that already traversed the retry ladder, since retry queues dead-letter back to the
  main exchange before re-delivery):
  ```java
  String receivedExchange = props.getReceivedExchange();
  String dlxExchange = receivedExchange.replaceFirst("\\.exchange$", ".dlx");
  String retryExchange = receivedExchange.replaceFirst("\\.exchange$", ".retry.exchange");
  ```
  This needs no new dependency for core — pure string manipulation on data already on the message.
  The existing `tierSuffix(int)` method is unaffected (only formats retry routing keys).
- **`SchemaMessagingConsumerAutoConfiguration` fix:** remove the `dlxExchange`/`retryExchange`
  `@Value` fields; update the `dlxMessageRecoverer(...)` bean method for the recoverer's smaller
  constructor; rewrite the class Javadoc describing the old global-exchange default.
- No change needed to `SchemaMessagingAutoConfiguration`, `TypeMapping`, `TypeMappingRegistry`, or
  the `RabbitAdmin` bean — `RabbitAdmin` auto-detects AMQP beans from anywhere in the context.
- Extend core's existing enforcer rule to also exclude `com.example:amqp-topology-kit`.

### D4 — Fix remaining references to the old global exchange / topology classes

- `producer-service/.../controller/OrderController.java`,
  `.../controller/CustomerController.java`: replace `EventExchanges.EVENTS_EXCHANGE` with
  `OrderEventRouting.EXCHANGE` / `CustomerEventRouting.EXCHANGE` (including the
  `/api/orders/poison` demo publish); drop the import.
- `consumer-service/src/test/java/com/example/consumer/DlxRoutingIT.java`: replace
  `EventExchanges.EVENTS_EXCHANGE` with `CustomerEventRouting.EXCHANGE`; drop the import. This
  test is the primary regression check for the D3 recoverer fix.
- `OrderCreatedIT.java`, `CustomerRegisteredIT.java`: replace the raw string literal
  `"events.exchange"` with `OrderEventRouting.EXCHANGE` / `CustomerEventRouting.EXCHANGE`.
- `consumer-service/.../health/QueueDepthHealthIndicator.java`: replace the import of
  `EventTopologyAutoConfiguration.DLQ_SUFFIX`/`QUEUE_SUFFIX` with
  `com.example.amqp.topology.TopologyNaming` (available transitively via the contracts modules);
  use `TopologyNaming.dlqName(rk)`/`queueName(rk)`.

### D5 — Documentation

- `spec/code-first-schema.md`: mark Guiding Principle 4/5 and D3 as superseded by this document
  (cross-reference, don't delete — historical record).
- `CONTEXT.md`: drop "Transport-agnostic — no Spring/AMQP on the classpath" from the Contract
  definition; drop "AMQP topology" from `schema-messaging-core`'s description; add
  `amqp-topology-kit` to the module table.
- `CLAUDE.md`: update the AMQP topology bullet, the module count ("Seven Maven modules" → eight),
  and the failure-model section's exchange names.
- `README.md`, `docs/TESTING-GUIDE.md`: generalize prose references to the old global exchange
  name.
- New ADR `docs/adr/0002-contract-owned-amqp-topology.md` recording the reversal (hard to reverse,
  contradicts an existing explicit spec, genuine trade-off between central genericity and
  per-domain ownership). Check whether `docs/adr/0001-code-first-schema-generation.md` (referenced
  by `CONTEXT.md`) actually exists — it was not found on disk during investigation, so it may need
  to be created alongside ADR-0002.

---

## Tasks

### Phase 1 — Shared kit module
- [x] **T-1.1** Create `amqp-topology-kit` module; register in root `<modules>`; add
  `spring-rabbit` dependency + test-scope `spring-boot-starter-test`; add its own enforcer rule.
- [x] **T-1.2** `TopologyNaming` + `EventTopologyFactory` (D1).
- [x] **T-1.3** `TopologyNamingTest` / `EventTopologyFactoryTest`.

### Phase 2 — Per-contract topology
- [x] **T-2.1** `OrderEventRouting` / `CustomerEventRouting`: add exchange + bean-name constants.
- [x] **T-2.2** `OrderTopologyAutoConfiguration` / `CustomerTopologyAutoConfiguration` +
  `AutoConfiguration.imports` in each contract module.
- [x] **T-2.3** `order-contracts`/`customer-contracts` POM updates: `spring-rabbit`,
  `spring-boot-autoconfigure`, `amqp-topology-kit`, new enforcer rule, updated module Javadoc.

### Phase 3 — Core deletions and fixes
- [x] **T-3.1** Delete `EventExchanges`, `RetryTopologyFactory`, `EventTopologyAutoConfiguration`;
  update core's `AutoConfiguration.imports`.
- [x] **T-3.2** Fix `DlxMessageRecoverer` (received-exchange-based resolution).
- [x] **T-3.3** Fix `SchemaMessagingConsumerAutoConfiguration` (drop `dlxExchange`/`retryExchange`
  `@Value` fields, update bean wiring + Javadoc).
- [x] **T-3.4** Extend core's enforcer rule to ban `amqp-topology-kit`.

### Phase 4 — Fix remaining references
- [x] **T-4.1** `OrderController`/`CustomerController` exchange references + poison demo.
- [x] **T-4.2** `DlxRoutingIT`, `OrderCreatedIT`, `CustomerRegisteredIT` exchange references.
- [x] **T-4.3** `QueueDepthHealthIndicator` import fix.

### Phase 5 — Documentation
- [x] **T-5.1** Update `CONTEXT.md`, `CLAUDE.md`, `README.md`, `docs/TESTING-GUIDE.md`.
- [x] **T-5.2** Mark `spec/code-first-schema.md` Principle 4/5 + D3 as superseded.
- [x] **T-5.3** Author `docs/adr/0002-contract-owned-amqp-topology.md` (and ADR-0001 if missing).

---

## Acceptance / Verification

1. `./mvnw clean install` — new module compiles; both new/extended enforcer rules pass; Spring
   context startup in producer/consumer resolves all topology + `TypeMapping` beans.
2. `docker compose up` (all-healthy) → start `producer-service` + `consumer-service` → RabbitMQ
   management UI shows 6 domain-scoped exchanges (`events.orders.{exchange,dlx,retry.exchange}`,
   `events.customers.{exchange,dlx,retry.exchange}`) and the old global 3 are gone; queue/DLQ names
   unchanged, just rebound.
3. Exercise all 6 REST endpoints; confirm messages land in the correct domain queues.
   `/api/orders/poison` → message lands in `orders.created.dlq` with correct `X-Failure-*` headers
   (proves both the publish-side and consumer-side recoverer fixes work together).
4. `DlxRoutingIT` passes — sharpest regression test for the D3 recoverer fix.
5. `./mvnw clean verify` — full reactor unit + integration test run.
6. One-time manual sanity check: temporarily add a banned dependency to `amqp-topology-kit` or
   `schema-messaging-core` and confirm the enforcer rules fail the build, then revert.
