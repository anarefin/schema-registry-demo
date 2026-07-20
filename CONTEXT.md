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
| **Contract** | A Maven module (`*-contracts`) holding event records, generated JSON Schemas, routing constants, the domain's **publisher-owned exchange** config (opt-in `*PublisherTopology`, not auto-loaded), and `TypeMapping` wiring (build-generated auto-loaded `GeneratedEventTypeMappings`). Per-service queues/DLQs/retry ladders are **not** declared here — see `ServiceQueueTopologyAutoConfiguration` in `schema-messaging-core`. |
| **Code-first / source of truth** | The Java **record** (with validation annotations) is authored by developers. The JSON Schema is **generated**, never hand-edited. |
| **Generated schema** | The committed `*.schema.json` file under `src/main/resources/schemas/`, produced by `schema-gen-tools` from the record via victools. |
| **`@GenerateSchema`** | Build-time marker on an event record (`event-contract-kit`). `schema-gen-tools` package-scans `target/classes` for this annotation at `process-classes`; nested value objects must not carry it. Must be paired with `@EventMapping`. |
| **`@EventMapping`** | Declarative schema identity + AMQP route on an event record (`groupId`, `exchange`, `routingKey`; optional `artifactId` / `schemaType`). Lives in `event-contract-kit`. Never declares an exchange. Reuse `*EventRouting` constants for exchange/routing key. Read at **build time** by `EventMappingProcessor` to emit `GeneratedEventTypeMappings`. See ADR-0010 (annotation) and ADR-0011 (build-time codegen). |
| **`GeneratedEventTypeMappings`** | Build-generated `@AutoConfiguration` (one per contracts module, in `<eventPackage>.topology`) emitted at the module's own `compile` by `schema-gen-tools`' `EventMappingProcessor` from each `@EventMapping` record. Holds one `@Bean @ConditionalOnMissingBean(name=…) TypeMapping` method per event, built via `Mappings`, methods FQCN-sorted for byte-stable output. Also emits `AutoConfiguration.imports` listing its FQCN — self-activates; no hand-written wrapper. Compiled by javac in the same build — zero runtime reflection, no index file. See ADR-0011. |
| **Registry group / artifact** | Apicurio coordinates: `group` (e.g. `events.orders`) + `artifact-id` (e.g. `OrderCreated`), used by `apicurio-registry-maven-plugin` (register/compat-check) as the CI-time identity for each event's schema. At runtime, `SchemaCoordinates(groupId, artifactId)` in `event-contract-kit` reuses the same pair only as a local lookup key into the classpath-baked schema catalog (`LocalSchemaCatalog`, in `schema-messaging-core`) — no registry call is made. |
| **TypeMapping** | Runtime wiring bean linking a Java record type → schema coordinates → schema type → RabbitMQ routing key → AMQP exchange. Lives in `event-contract-kit`, registered by each domain's build-generated `GeneratedEventTypeMappings` `@Bean` methods (no runtime reflection or index read — ADR-0011). Bean name = `Introspector.decapitalize(simpleName) + "Mapping"`. `EventPublisher.publish(Object event)` resolves both exchange and routing key from it — the caller supplies only the event. |
| **DomainTopology** / **DomainExchanges** | `event-contract-kit` factory: `DomainTopology.of(mainExchangeName)` builds the three domain-scoped `TopicExchange`s (main, DLX, retry) as a `DomainExchanges` record. DLX/retry names derive via `TopologyNaming`. Used by each `*PublisherTopology` to publish the exchange beans, and by `ServiceQueueTopologyAutoConfiguration` to build the (undeclared) exchange objects it binds queues to. |
| **Publisher-owned exchanges** | The three domain exchanges (`events.{domain}.exchange` / `.dlx` / `.retry.exchange`) are declared by the domain's **single publisher** (producer-of-record), which `@Import`s the opt-in `*PublisherTopology`. Since each domain has exactly one publisher, ownership is unambiguous; a contracts jar alone declares no exchanges. See ADR-0008. |
| **Consumer-owned private queues** | Each consumer declares only its own `{routingKey}.{serviceName}.queue` (+ `.dlq` + retry-ladder) and *binds* them to the publisher-owned exchanges — it never declares the exchanges. Declared by `ServiceQueueTopologyAutoConfiguration` from the `@BitsEventHandler` scan. A consumer that boots before its publisher self-heals via `RabbitAdmin.ignoreDeclarationExceptions(true)`. |
| **Least-privilege broker roles** | The ownership split lets each role run on RabbitMQ credentials scoped to what it touches: the **publisher** needs `configure`+`write` on its domain exchanges (declare + publish) and no queue rights; the **consumer** needs `configure`+`write`+`read` on its own queues (declare + bind-destination + consume) plus `read` on the exchanges (bind-source), and **no `configure` on any exchange**. Not enforced in this POC (`guest`/`guest`), but expressible because declaration is partitioned by role. See ADR-0008. |
| **Mappings** | `event-contract-kit` domain-scoped builder: `Mappings.forDomain(group, exchange).json(javaType, routingKey)` produces a `TypeMapping` with `SchemaType.JSON` and defaults `artifactId` to the Java type's simple name. The generated config (and any app-level override / test) must build mappings through it — single construction path. |
| **Bean-name override vs selection** | **Override:** define `@Bean("orderCreatedMapping")` (same name the generated config uses); the generated method is `@ConditionalOnMissingBean(name=…)`, so the app bean wins. **Selection:** `events.mappings.include` / `exclude` filter by simple name, FQCN, or `groupId:artifactId` — **not** Spring bean names. Different knobs. |
| **Generated-config package** | `EventMappingProcessor` emits `GeneratedEventTypeMappings` into `<eventPackage>.topology`, where `<eventPackage>` is the package shared by the module's `@EventMapping` records (longest-common-package fallback). Keep a domain's event records in one package (e.g. `com.example.contracts.orders`). |
| **`@BitsEventHandler`** | Marker annotation (`schema-messaging-core`) for a listener method with only an event-typed parameter — no queue name, no container factory. `BitsEventHandlerRegistrar` (a `RabbitListenerConfigurer`) resolves the queue at startup from the parameter's `TypeMapping` and registers the endpoint programmatically against the shared `rabbitListenerContainerFactory`. The listener stack is auto-gated on handler presence via `OnBitsEventHandlerPresentCondition`. |
| **Local schema catalog** | `LocalSchemaCatalog` (`schema-messaging-core`): eagerly loads every registered event's JSON Schema from the classpath (`schemas/<kebab-case-name>.schema.json`, computed by `SchemaFileNaming`) at application startup, and fails fast if a schema is missing or malformed. No network calls, no cache TTL — the runtime never talks to Apicurio Registry. |
| **Drift gate** | CI check: regenerate schemas via `schema-gen-tools`, fail if committed files differ (`git diff --exit-code`). |
| **Compat gate** | CI check: dry-run `apicurio-registry:register` against the standing registry; incompatible changes block merge. |

## Modules

| Module | Role |
|--------|------|
| `schema-gen-tools` | Build-only. (1) Schema generator (victools): discovers `@GenerateSchema` types via package scan at `process-classes`, requires paired `@EventMapping`, writes schemas. (2) `EventMappingProcessor` (APT): reads `@EventMapping` at each contracts module's `compile` and emits the `GeneratedEventTypeMappings` `@AutoConfiguration` + `AutoConfiguration.imports`. Never on service classpath. |
| `schema-messaging-core` | Domain-agnostic messaging library (converter, `LocalSchemaCatalog`, publisher, DLX/retry routing, `@BitsEventHandler` registration, per-service queue topology via `ServiceQueueTopologyAutoConfiguration`). Owns **per-service queues/DLQs/retry ladders** for handled events; does **not** own domain exchanges. Depends on `event-contract-kit` for `TypeMapping`/`SchemaCoordinates`/`SchemaType`. No mapping discovery of its own. |
| `event-contract-kit` (formerly `amqp-topology-kit`) | Domain-agnostic AMQP topology-building library (naming conventions, retry-ladder factory) plus `TypeMapping`/`SchemaCoordinates`/`SchemaType`, `@GenerateSchema`, `@EventMapping`, and the `Mappings` builder. Used by both `*-contracts` (exchanges + generated mappings) and `schema-messaging-core` (per-service queues). |
| `order-contracts` / `customer-contracts` | Event records (`@GenerateSchema` + `@EventMapping`), generated schemas, opt-in publisher-owned **exchange** config (`*PublisherTopology`), and build-generated auto-loaded `GeneratedEventTypeMappings` per domain. |
| `producer-service` / `consumer-service` | Spring Boot demo apps (:8081 / :8082). |

## AMQP topology ownership

| Layer | Owner | What gets declared |
|-------|-------|-------------------|
| Domain exchanges | the **one** publisher, via opt-in `*PublisherTopology` in `*-contracts` (`@Import`-ed, not auto-loaded) | `events.{orders,customers}.{exchange,dlx,retry.exchange}` |
| Per-service queues + bindings | `schema-messaging-core` (`ServiceQueueTopologyAutoConfiguration`), on **each** consumer | `{routingKey}.{serviceName}.queue`, `.dlq`, `.retry.{tier}` — one set per handled `@BitsEventHandler` event; binds to (never declares) the publisher-owned exchanges |

The exchange/queue split maps onto **least-privilege broker roles** (publisher: `configure`+`write`
on exchanges; consumer: own-queue rights + `read` on exchanges, no exchange `configure`). See
ADR-0008 and `docs/TUTORIAL.md` §2 and §4.5 for the full walkthrough.

## Schema evolution and versioning

| Term | Meaning |
|------|---------|
| **Compatible evolution** | The only supported way a schema changes here ("Tier 1"). Additive changes pass the FORWARD gate and get a new auto-assigned registry version. Breaking changes are **unsupported** — an escalation, not a workflow. A breaking change cannot be "version 2" of an artifact: the compat gate rejects it, correctly. See ADR-0009. |
| **Expand/contract** (parallel change) | The sanctioned route for an apparently-breaking change, using no new mechanism: add the new field alongside the old (additive, passes FORWARD), migrate readers, then remove the old field once provably unread. One hard break becomes three easy steps. |
| **Tolerant reader** | The runtime's willingness to accept a payload carrying fields it does not know — the half of the compatibility contract the registry does **not** provide. Two props: generated schemas emit `"additionalProperties": true` on every object node (tree-walk enforced in `schema-gen-tools`), and messaging deserialization uses a factory-owned Jackson `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false` that cannot be displaced by an app `@Bean ObjectMapper` (ADR-0009 / Phase 1; defended by `MessagingObjectMapperIsolationTest`). |
| **FORWARD** | The compatibility rule on all seven artifacts. Mechanically: Apicurio classifies adding a property as `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`, which BACKWARD rejects and FORWARD accepts. Directionally — and this is the real meaning — **the producer upgrades first and consumers lag safely**. That is a deployment-order commitment, not a lint setting. |
| **`contentHash`** | Apicurio's content-addressed schema identifier. The only one of `globalId` / `contentId` / `contentHash` computable at **build time** — the other two are assigned by the registry and unknowable without calling it. If a version identifier ever reaches the wire it must be this one, and it is **advisory**: a classpath-resolved consumer can never gate on a version, because it cannot fetch a schema it does not ship (ADR-0009). |
| **Version state** | Apicurio's per-version lifecycle: `ENABLED` / `DISABLED` / `DEPRECATED`, set via `PUT /groups/{g}/artifacts/{a}/versions/{v}/state`. `DEPRECATED` signals through a warning header on the REST response **when content is fetched** — and this runtime never fetches content, so it is a **CI/governance signal only**, with no runtime effect here. |
| **Schema drift** | Two distinct meanings. *Build-time:* committed schemas differ from regenerated ones — caught by the **drift gate**. *Runtime (hypothetical, not implemented):* a consumer receives a message stamped with a `contentHash` it does not have — a **signal, not a failure**, and the evidence needed before flipping a version to `DEPRECATED`. |
