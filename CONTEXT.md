# Glossary — Schema Registry POC

Quick reference for domain terms used across this repository.

## Domains

| Domain | Registry group | Contract module |
|--------|----------------|-----------------|
| **Order** | `events.orders` | `order-contracts` |
| **Customer** | `events.customers` | `customer-contracts` |

An **Order** is a commerce transaction identified by `orderId`. A **Customer** is a registered
account identified by `customerId`.

## Events (seven artifacts)

### Orders (`events.orders`)

| Event | Description |
|-------|-------------|
| `OrderCreated` | New order placed (line items, totals, currency) |
| `OrderShipped` | Order dispatched with carrier + tracking number |
| `OrderCancelled` | Order cancelled with reason and optional refund |
| `OrderFulfilled` | Order fulfilled with buyer, shipping, and payment details |

### Customers (`events.customers`)

| Event | Description |
|-------|-------------|
| `CustomerRegistered` | New customer account (email, name, optional phone) |
| `CustomerAddressAdded` | Mailing address added (nested `Address` object) |
| `CustomerTierChanged` | Loyalty tier change (`CustomerTier` enum) |

## Contract terminology

| Term | Meaning |
|------|---------|
| **Contract** | A Maven module (`*-contracts`) holding event records, generated JSON Schemas, routing constants, the domain's **publisher-owned exchange** config (opt-in `*PublisherTopology`, not auto-loaded), and `TypeMapping` wiring (auto-loaded `*TypeMappingAutoConfiguration`). Per-service queues/DLQs/retry ladders are **not** declared here — see `ServiceQueueTopologyAutoConfiguration` in `schema-messaging-core`. |
| **Code-first / source of truth** | The Java **record** (with validation annotations) is authored by developers. The JSON Schema is **generated**, never hand-edited. |
| **Generated schema** | The committed `*.schema.json` file under `src/main/resources/schemas/`, produced by `schema-gen-tools` from the record via victools. |
| **`@GenerateSchema`** | Build-time marker on an event record (`event-contract-kit`). `schema-gen-tools` package-scans `target/classes` for this annotation at `process-classes`; nested value objects must not carry it. |
| **Registry group / artifact** | Apicurio coordinates: `group` (e.g. `events.orders`) + `artifact-id` (e.g. `OrderCreated`), used by `apicurio-registry-maven-plugin` (register/compat-check) as the CI-time identity for each event's schema. At runtime, `SchemaCoordinates(groupId, artifactId)` in `event-contract-kit` reuses the same pair only as a local lookup key into the classpath-baked schema catalog (`LocalSchemaCatalog`, in `schema-messaging-core`) — no registry call is made. |
| **TypeMapping** | Runtime wiring bean linking a Java record type → schema coordinates → schema type → RabbitMQ routing key → AMQP exchange. Lives in `event-contract-kit`, registered in each domain's `*-contracts` module via its `*TypeMappingAutoConfiguration`. `EventPublisher.publish(Object event)` resolves both exchange and routing key from it — the caller supplies only the event. |
| **DomainTopology** / **DomainExchanges** | `event-contract-kit` factory: `DomainTopology.of(mainExchangeName)` builds the three domain-scoped `TopicExchange`s (main, DLX, retry) as a `DomainExchanges` record. DLX/retry names derive via `TopologyNaming`. Used by each `*PublisherTopology` to publish the exchange beans, and by `ServiceQueueTopologyAutoConfiguration` to build the (undeclared) exchange objects it binds queues to. |
| **Publisher-owned exchanges** | The three domain exchanges (`events.{domain}.exchange` / `.dlx` / `.retry.exchange`) are declared by the domain's **single publisher** (producer-of-record), which `@Import`s the opt-in `*PublisherTopology`. Since each domain has exactly one publisher, ownership is unambiguous; a contracts jar alone declares no exchanges. See ADR-0008. |
| **Consumer-owned private queues** | Each consumer declares only its own `{routingKey}.{serviceName}.queue` (+ `.dlq` + retry-ladder) and *binds* them to the publisher-owned exchanges — it never declares the exchanges. Declared by `ServiceQueueTopologyAutoConfiguration` from the `@BitsEventHandler` scan. A consumer that boots before its publisher self-heals via `RabbitAdmin.ignoreDeclarationExceptions(true)`. |
| **Least-privilege broker roles** | The ownership split lets each role run on RabbitMQ credentials scoped to what it touches: the **publisher** needs `configure`+`write` on its domain exchanges (declare + publish) and no queue rights; the **consumer** needs `configure`+`write`+`read` on its own queues (declare + bind-destination + consume) plus `read` on the exchanges (bind-source), and **no `configure` on any exchange**. Not enforced in this POC (`guest`/`guest`), but expressible because declaration is partitioned by role. See ADR-0008. |
| **Mappings** | `event-contract-kit` domain-scoped builder: `Mappings.forDomain(group, exchange).json(javaType, routingKey)` produces a `TypeMapping` with `SchemaType.JSON` and defaults `artifactId` to the Java type's simple name. Each `*TypeMappingAutoConfiguration` delegates to it. |
| **`@BitsEventHandler`** | Marker annotation (`schema-messaging-core`) for a listener method with only an event-typed parameter — no queue name, no container factory. `BitsEventHandlerRegistrar` (a `RabbitListenerConfigurer`) resolves the queue at startup from the parameter's `TypeMapping` and registers the endpoint programmatically against the shared `rabbitListenerContainerFactory`. |
| **Local schema catalog** | `LocalSchemaCatalog` (`schema-messaging-core`): eagerly loads every registered event's JSON Schema from the classpath (`schemas/<kebab-case-name>.schema.json`, computed by `SchemaFileNaming`) at application startup, and fails fast if a schema is missing or malformed. No network calls, no cache TTL — the runtime never talks to Apicurio Registry. |
| **Drift gate** | CI check: regenerate schemas via `schema-gen-tools`, fail if committed files differ (`git diff --exit-code`). |
| **Compat gate** | CI check: dry-run `apicurio-registry:register` against the standing registry; incompatible changes block merge. |

## Modules

| Module | Role |
|--------|------|
| `schema-gen-tools` | Build-only schema generator (victools). Discovers `@GenerateSchema` types via package scan. Never on service classpath. |
| `schema-messaging-core` | Domain-agnostic messaging library (converter, `LocalSchemaCatalog`, publisher, DLX/retry routing, `@BitsEventHandler` registration, per-service queue topology via `ServiceQueueTopologyAutoConfiguration`). Owns **per-service queues/DLQs/retry ladders** for handled events; does **not** own domain exchanges. Depends on `event-contract-kit` for `TypeMapping`/`SchemaCoordinates`/`SchemaType`. |
| `event-contract-kit` (formerly `amqp-topology-kit`) | Domain-agnostic AMQP topology-building library (naming conventions, retry-ladder factory) plus the shared `TypeMapping`/`SchemaCoordinates`/`SchemaType` data types and `@GenerateSchema`. Used by both `*-contracts` (exchanges) and `schema-messaging-core` (per-service queues). |
| `order-contracts` / `customer-contracts` | Event records, generated schemas, the opt-in publisher-owned **exchange** config (`*PublisherTopology`), and `TypeMapping` wiring per domain. |
| `producer-service` / `consumer-service` | Spring Boot demo apps (:8081 / :8082). |

## AMQP topology ownership

| Layer | Owner | What gets declared |
|-------|-------|-------------------|
| Domain exchanges | the **one** publisher, via opt-in `*PublisherTopology` in `*-contracts` (`@Import`-ed, not auto-loaded) | `events.{orders,customers}.{exchange,dlx,retry.exchange}` |
| Per-service queues + bindings | `schema-messaging-core` (`ServiceQueueTopologyAutoConfiguration`), on **each** consumer | `{routingKey}.{serviceName}.queue`, `.dlq`, `.retry.{tier}` — one set per handled `@BitsEventHandler` event; binds to (never declares) the publisher-owned exchanges |

The exchange/queue split maps onto **least-privilege broker roles** (publisher: `configure`+`write`
on exchanges; consumer: own-queue rights + `read` on exchanges, no exchange `configure`). See
ADR-0008 and `docs/TUTORIAL.md` §2 and §4.5 for the full walkthrough.
