# ADR-0008: Publisher-owned exchanges, consumer-owned queues

**Status:** Accepted  
**Date:** 2026-07-15  
**Spec:** `spec/11-separate-publisher-consumer-topology-ownership.md`  
**Related findings:** ARCH-009  
**Supersedes:** the exchange-ownership half of ADR-0007 ("contracts own exchanges")

## Context

ADR-0007 left each `*-contracts` module auto-declaring its three domain `TopicExchange` beans
(main / `.dlx` / `.retry.exchange`) via an auto-imported `*TopologyAutoConfiguration`. A
`TopicExchange` is a Spring AMQP `Declarable`, so **any** service with the contracts jar on its
classpath had `RabbitAdmin` declare all three exchanges on the broker — regardless of whether the
service publishes, consumes, or merely transitively depends on the domain.

That is wrong along two axes:

- **Role.** A pure publisher declared the DLX/retry exchanges it never touches; a pure consumer
  declared the main exchange it only binds to. `schema-messaging-core` additionally injected
  `List<TopicExchange>` and re-declared exchanges a second time while building its per-service
  queue bindings.
- **Least privilege.** Because every role declared every exchange, every service needed
  `configure` permission on every exchange. There was no way to hand a consumer credentials that
  cannot mutate the broker's exchange topology.

The domain fact that makes a cleaner split unambiguous: **each domain is published by exactly one
project (producer-of-record) and consumed by many.** Ownership of the exchanges is therefore not a
coordination problem — it belongs to the single publisher.

Two implementation facts make the split safe (both predate this ADR):

- `EventPublisher.publish()` sends via `mapping.exchange()` — a **string**. The publisher needs the
  exchange to *exist* on the broker, not the bean in its context.
- A `Binding` holds the exchange **name**; declaring a binding does not require the app to declare
  the exchange. So a consumer can bind its private queues to an exchange it never declares.

## Decision

Split topology declaration by ownership cardinality:

| Topology | Owner | Declared by |
|---|---|---|
| Exchanges: main / DLX / retry (per domain) | the **one** publisher | that publisher, via an opt-in `*PublisherTopology` it `@Import`s |
| Per-service queue + DLQ + retry ladder + **bindings** | **each** consumer | `ServiceQueueTopologyAutoConfiguration` in `schema-messaging-core`, from the `@BitsEventHandler` scan — binds to the publisher-owned exchanges, never declares them |
| Records / schemas / `TypeMapping` / `*EventRouting` | vocabulary | nobody declares broker topology |

### Contracts

Each `*-contracts` module replaces its auto-imported `*TopologyAutoConfiguration` with a plain,
**not auto-imported** `*PublisherTopology` `@Configuration` — the same three exchange `@Bean`s
(from `DomainTopology.of(*EventRouting.EXCHANGE)`, each `@ConditionalOnMissingBean(name=...)`) but
deliberately **absent** from `META-INF/spring/.../AutoConfiguration.imports`. A contracts jar on
the classpath now forces **no** exchanges. `*TypeMappingAutoConfiguration` stays auto-loaded — the
`TypeMapping` beans are plain data both roles need.

### Core

`ServiceQueueTopologyConfigurer` drops the `List<TopicExchange>` injection, `exchangesByName`,
`requireExchange`, and every `declareExchangeOnce(...)` call. It rebuilds the main/DLX/retry
`TopicExchange` objects **locally** from `DomainTopology.of(mapping.exchange())` solely to feed
`EventTopologyFactory.declarablesForEvent(...)`, then declares **only** the resulting queues and
bindings. Core invokes `declareExchange` zero times and stays `core ↛ contracts`-compliant
(kit + exchange-name string only). The consumer `RabbitAdmin` is set
`ignoreDeclarationExceptions(true)` so a consumer that boots before its publisher self-heals on
reconnect instead of failing context refresh.

### Services

`producer-service`, the sole publisher of both domains, `@Import`s both `OrderPublisherTopology`
and `CustomerPublisherTopology`, declaring all six exchanges at startup. `consumer-service`
production code is unchanged: it imports no topology and binds its queues to the publisher-owned
exchanges. (Its Testcontainers ITs import a `*PublisherTopology` / tiny test config so the
exchanges exist for the bindings.)

### Least-privilege broker roles (what the split enables)

With declaration cleanly partitioned, each role can run on credentials scoped to exactly what it
touches. RabbitMQ maps AMQP operations onto three per-vhost permission verbs (`configure` =
declare/delete a resource, `write` = publish to an exchange / bind as destination, `read` = consume
from a queue / bind as source):

| Principal | Domain exchanges (`events.{domain}.exchange` / `.dlx` / `.retry.exchange`) | Own private queues (`{routingKey}.{service}.queue` / `.dlq` / `.retry.*`) |
|---|---|---|
| **Publisher** (`producer-service`) | `configure` (declare) + `write` (publish) | — declares no queues |
| **Consumer** (`consumer-service`) | `read` only (bind source) — **no `configure`, no `write`** | `configure` (declare) + `write` (bind destination) + `read` (consume) |

The consumer never needs `configure` on any exchange, and the publisher never needs `read`. This
POC runs on `guest`/`guest` with full access, so the model is not enforced here — but the ownership
split is what makes such credentials expressible at all.

## Options weighed

### A. Publisher-owns (chosen)

The single producer-of-record declares the domain exchanges; consumers bind only. Matches domain
cardinality exactly, needs no cross-service coordination, and yields the least-privilege split
above.

### B. Central `definitions.json` provisioning — rejected

Ship exchanges in a broker-loaded `definitions.json` (or a provisioning job), so no application
declares them. Correct for a many-publisher / operator-controlled topology and for strict
boot-order independence, but over-engineered for a single-publisher-per-domain model: it adds an
out-of-band artifact to keep in sync with the code-first `*EventRouting` constants. Kept only as a
documented opt-in for deployments that want application-independent topology.

### C. Each-app idempotent declaration — rejected

Every service idempotently declares the exchanges it uses. Simple, but it spreads `configure` on
the domain exchanges across the **many** consumers — defeating the least-privilege goal and
re-creating the double-declaration this ADR removes.

## Consequences

- **Positive:** Ownership follows domain cardinality; the one publisher owns its exchanges, each
  consumer owns only its private queues. A contracts jar alone declares no broker topology.
- **Positive:** Least-privilege broker credentials become expressible — consumers need no
  `configure` on exchanges, publishers no `read`.
- **Positive:** Core's cross-role reach into exchange beans is gone; it declares only queues +
  bindings and stays `core ↛ contracts`-compliant.
- **Neutral / handled:** Boot order is no longer guaranteed (a consumer may start before its
  publisher). Handled by `ignoreDeclarationExceptions(true)` on the consumer `RabbitAdmin`: the
  binding declaration is retried on reconnect and self-heals once the publisher declares the
  exchange. No message loss — the publisher cannot emit before its own exchanges exist.
- **Reversal:** This reverses ADR-0007's "contracts own exchanges (auto-declared for every
  dependent)" decision. The `DomainTopology` / `Mappings` factories from ADR-0007 are unchanged and
  still used — only *who declares the beans, and whether they auto-load* changed.
