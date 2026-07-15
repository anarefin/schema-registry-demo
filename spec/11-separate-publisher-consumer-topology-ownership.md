# 11 — Separate publisher/consumer topology ownership (publisher-owned exchanges, consumer-owned queues)

**Severity:** Medium · **Finding:** ARCH-009 · **Type:** AFK

## What to build

Today every service that depends on an `*-contracts` module gets **3 unconditional `TopicExchange`
`@Bean`s** per domain (main/`.dlx`/`.retry.exchange`) via
[OrderTopologyAutoConfiguration](order-contracts/src/main/java/com/example/contracts/orders/topology/OrderTopologyAutoConfiguration.java)
(+ customer mirror). `TopicExchange` is a Spring AMQP `Declarable`, so `RabbitAdmin` auto-declares
**all 6** exchanges on the broker regardless of role — a pure publisher declares DLX/retry it never
uses, and every service declares exchanges for domains it neither publishes nor consumes. Queues are
already demand-driven (`@BitsEventHandler` scan), so only the exchange beans are the problem.

**Ownership constraint (domain fact):** each domain is published by **exactly one** project
(producer-of-record) and consumed by **many**. Ownership is therefore unambiguous — the single
publisher owns the domain's exchanges; each consumer owns only its private queues. No central
provisioning is needed.

Two facts make this safe:
- [EventPublisher.publish()](schema-messaging-core/src/main/java/com/example/messaging/core/publisher/EventPublisher.java)
  sends via `mapping.exchange()` (a **string**) — the publisher needs the exchange to *exist*, not the bean.
- The only consumer of the exchange beans is
  [ServiceQueueTopologyConfigurer](schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java#L110-L128);
  it injects `List<TopicExchange>` only to build `Binding`s (a `Binding` holds the exchange **name** —
  declaring a binding does not require the app to declare the exchange). No code references the exchange
  beans by name.

Split topology declaration by ownership cardinality:

| Topology | Owner | Declared by |
|---|---|---|
| Exchanges: main / DLX / retry (per domain) | the **one** publisher | that publisher, via an opt-in `*PublisherTopology` it `@Import`s |
| Per-service queue + DLQ + retry ladder + bindings | **each** consumer (`{routingKey}.{serviceName}.queue`) | the consumer at startup (existing `@BitsEventHandler` scan) — **binds to** the publisher-owned exchanges, never declares them |
| Records / schemas / `TypeMapping` / `*EventRouting` | vocabulary | nobody declares broker topology |

**`order-contracts` / `customer-contracts`** — replace `*TopologyAutoConfiguration` (unconditional
auto beans) with a plain, **not auto-imported** `*PublisherTopology` `@Configuration` that declares the
domain's three exchanges from `DomainTopology.of(*EventRouting.EXCHANGE)` (`.main()/.dlx()/.retry()` as
`@Bean TopicExchange`, each `@ConditionalOnMissingBean(name=...)`). Remove `*TopologyAutoConfiguration`
from the module's `META-INF/spring/.../AutoConfiguration.imports`; **keep** `*TypeMappingAutoConfiguration`
(plain-data beans, needed by both roles). Records, schemas, `*EventRouting` constants unchanged.

**`schema-messaging-core`** — in
[ServiceQueueTopologyAutoConfiguration](schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java)
drop the `List<TopicExchange>` param / `exchangesByName` / `requireExchange`; build exchange objects
locally via `DomainTopology.of(mapping.exchange())` solely to pass into
`EventTopologyFactory.declarablesForEvent(...)`; **remove the `declareExchangeOnce(...)` calls** (core
declares only queues + bindings, never exchanges). Set the consumer `RabbitAdmin`
`ignoreDeclarationExceptions(true)` so a consumer that boots before the publisher self-heals on
reconnect instead of failing context refresh. Stays `core ↛ contracts`-compliant (kit + string only).

**`producer-service`** (the sole publisher of both domains) — add
`@Import({OrderPublisherTopology.class, CustomerPublisherTopology.class})`; declares all 6 exchanges at
startup. **`consumer-service`** — no production change; its Testcontainers ITs must import a
`*PublisherTopology` (or a tiny test config) so exchanges exist for bindings.

**Explicitly rejected:** central `definitions.json` provisioning (over-engineered for a single
publisher — kept only as a documented opt-in for strict boot-order independence); each-app idempotent
declaration (spreads `configure` on exchanges across the many consumers); separate Maven modules /
property-based role flags.

## Acceptance criteria

- [ ] Each `*-contracts` module exposes an opt-in `*PublisherTopology` (`main`/`dlx`/`retry` `@Bean`s
      from `DomainTopology.of(...)`, `@ConditionalOnMissingBean(name=...)`), and no longer lists a
      topology class in `AutoConfiguration.imports`; `*TypeMappingAutoConfiguration` still auto-loads.
- [ ] A context with a contracts jar on the classpath but no `@Import(*PublisherTopology)` and no
      `@BitsEventHandler` declares **zero** `Declarable` beans and invokes `declareExchange` **zero**
      times (new `ApplicationContextRunner` regression test in core).
- [ ] `ServiceQueueTopologyConfigurer` declares only queues + bindings and **never** calls
      `rabbitAdmin.declareExchange(...)`;
      [ServiceQueueTopologyExchangeDeduplicationTest](schema-messaging-core/src/test/java/com/example/messaging/core/config/ServiceQueueTopologyExchangeDeduplicationTest.java)
      is repurposed to assert this, and
      [ServiceQueueTopologyLegacyDecommissionTest](schema-messaging-core/src/test/java/com/example/messaging/core/config/ServiceQueueTopologyLegacyDecommissionTest.java)
      drops its `TopicExchange` fixtures.
- [ ] `producer-service` imports both `*PublisherTopology` configs; a consumer that boots first comes up
      cleanly (resilient declaration) and its queues bind once the producer declares the exchanges.
- [ ] `./mvnw clean verify` passes: `process-classes` regenerates identical schemas
      (`git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'`), and Testcontainers ITs
      (with exchanges provided by an imported `*PublisherTopology`) flow a message end-to-end and the
      legacy-decommission IT stays green.
- [ ] Docs: new `docs/adr/ADR-0008-publisher-owned-messaging-topology.md` (problem; publisher-owns vs
      central-provisioning vs each-app-declare; decision; consequences — reverses "contracts own
      exchanges", adds least-privilege consumers + boot-order handling); `CONTEXT.md` glossary gains
      *publisher-owned exchanges* / *consumer-owned private queues* / *least-privilege broker roles*;
      the `*-contracts` / `schema-messaging-core` bullets in `CLAUDE.md`, plus `docs/TUTORIAL.md` and
      `README.md`, describe publisher-owned exchanges + consumer-owned queues and the permission split
      (publisher `configure`+`write` on its domain exchanges; consumer `read`/`bind` + own-queue
      `configure`; no consumer `configure` on exchanges).

## Blocked by

None — can start immediately.
