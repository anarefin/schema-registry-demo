# Glossary — Schema Registry POC

Quick reference for domain terms used across this repository.

## Domains

| Domain | Registry group | Contract module |
|--------|----------------|-----------------|
| **Order** | `events.orders` | `order-contracts` |
| **Customer** | `events.customers` | `customer-contracts` |

An **Order** is a commerce transaction identified by `orderId`. A **Customer** is a registered
account identified by `customerId`.

## Events (six artifacts)

### Orders (`events.orders`)

| Event | Description |
|-------|-------------|
| `OrderCreated` | New order placed (line items, totals, currency) |
| `OrderShipped` | Order dispatched with carrier + tracking number |
| `OrderCancelled` | Order cancelled with reason and optional refund |

### Customers (`events.customers`)

| Event | Description |
|-------|-------------|
| `CustomerRegistered` | New customer account (email, name, optional phone) |
| `CustomerAddressAdded` | Mailing address added (nested `Address` object) |
| `CustomerTierChanged` | Loyalty tier change (`CustomerTier` enum) |

## Contract terminology

| Term | Meaning |
|------|---------|
| **Contract** | A Maven module (`*-contracts`) holding event records, generated JSON Schemas, routing constants, and — since `spec/contract-owned-amqp-topology.md` — that domain's AMQP topology auto-configuration (exchanges, queues, DLQs, retry ladder). |
| **Code-first / source of truth** | The Java **record** (with validation annotations) is authored by developers. The JSON Schema is **generated**, never hand-edited. |
| **Generated schema** | The committed `*.schema.json` file under `src/main/resources/schemas/`, produced by `schema-gen-tools` from the record via victools. |
| **`@GenerateSchema`** | Build-time marker on an event record (`event-contract-kit`). `schema-gen-tools` package-scans `target/classes` for this annotation at `process-classes`; nested value objects must not carry it. |
| **Registry group / artifact** | Apicurio coordinates: `group` (e.g. `events.orders`) + `artifact-id` (e.g. `OrderCreated`), used by `apicurio-registry-maven-plugin` (register/compat-check) as the CI-time identity for each event's schema. At runtime, `SchemaCoordinates(groupId, artifactId)` in `event-contract-kit` (ADR-0006) reuses the same pair only as a local lookup key into the classpath-baked schema catalog (`LocalSchemaCatalog`, in `schema-messaging-core`) — no registry call is made. See [ADR-0004](docs/adr/0004-local-schema-validation.md). |
| **TypeMapping** | Runtime wiring bean linking a Java record type → schema coordinates (CI-governance identity, also used as the local classpath-schema lookup key) → schema type → RabbitMQ routing key → AMQP exchange (exchange field added by ADR-0007). Lives in `event-contract-kit` (ADR-0006), registered in each domain's `*-contracts` module via its `*TypeMappingAutoConfiguration` (pattern established by ADR-0005). `EventPublisher.publish(Object event)` resolves both exchange and routing key from it — the caller supplies only the event. |
| **`@BitsEventHandler`** | Marker annotation (`schema-messaging-core`) for a listener method with only an event-typed parameter — no queue name, no container factory. `BitsEventHandlerRegistrar` (a `RabbitListenerConfigurer`, ADR-0008) resolves the queue at startup from the parameter's `TypeMapping` and registers the endpoint programmatically against the shared `rabbitListenerContainerFactory`, replacing `@RabbitListener(queues = ...)` on `OrderEventListener`/`CustomerEventListener`. |
| **Local schema catalog** | `LocalSchemaCatalog` (`schema-messaging-core`): eagerly loads every registered event's JSON Schema from the classpath (`schemas/<kebab-case-name>.schema.json`, computed by `SchemaFileNaming`) at application startup, and fails fast if a schema is missing or malformed. No network calls, no cache TTL — the runtime never talks to Apicurio Registry. |
| **Drift gate** | CI check: regenerate schemas via `schema-gen-tools`, fail if committed files differ (`git diff --exit-code`). |
| **Compat gate** | CI check: dry-run `apicurio-registry:register` against the standing registry; incompatible changes block merge. |

## Modules

| Module | Role |
|--------|------|
| `schema-gen-tools` | Build-only schema generator (victools). Discovers `@GenerateSchema` types via package scan. Never on service classpath. |
| `schema-messaging-core` | Domain-agnostic messaging library (converter, `LocalSchemaCatalog`, schema-validation wiring). No AMQP topology — see `event-contract-kit`. Depends on `event-contract-kit` for `TypeMapping`/`SchemaCoordinates`/`SchemaType` (ADR-0006). |
| `event-contract-kit` (formerly `amqp-topology-kit`) | Domain-agnostic AMQP topology-building library (naming conventions, retry-ladder factory) plus the shared `TypeMapping`/`SchemaCoordinates`/`SchemaType` data types (ADR-0006) and `@GenerateSchema`. Depended on by `order-contracts`, `customer-contracts`, and `schema-messaging-core`. |
| `producer-service` / `consumer-service` | Spring Boot demo apps (:8081 / :8082). |

See `docs/adr/0001-code-first-schema-generation.md` for the code-first schema decision,
`docs/adr/0002-contract-owned-amqp-topology.md` for the AMQP topology ownership reversal,
`docs/adr/0004-local-schema-validation.md` for the removal of runtime Apicurio dependency,
`docs/adr/0005-contracts-may-depend-on-core.md` (superseded) for the one-directional
`contracts → core` dependency amendment,
`docs/adr/0006-typemapping-relocated-to-event-contract-kit.md` for how that dependency was
closed again by relocating `TypeMapping`/`SchemaCoordinates`/`SchemaType` into `event-contract-kit`,
`docs/adr/0007-typemapping-carries-exchange.md` for why `TypeMapping` grew an `exchange` field
(amends ADR-0006's field scoping), and
`docs/adr/0008-bitsevenhandler-programmatic-listener-registration.md` for `@BitsEventHandler`,
the programmatic listener-registration mechanism that replaced `@RabbitListener` on
`OrderEventListener`/`CustomerEventListener`. Both are the code-level detail behind
`spec/simplified-publish-and-listen.md`.
