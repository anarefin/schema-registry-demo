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
| **Registry group / artifact** | Apicurio coordinates: `group` (e.g. `events.orders`) + `artifact-id` (e.g. `OrderCreated`). Each event maps to one artifact with a **FORWARD** compatibility rule. |
| **TypeMapping** | Runtime wiring bean linking a Java record type → registry coordinates → schema type → RabbitMQ routing key. Registered in producer/consumer `*ContractsConfiguration`. |
| **Drift gate** | CI check: regenerate schemas via `schema-gen-tools`, fail if committed files differ (`git diff --exit-code`). |
| **Compat gate** | CI check: dry-run `apicurio-registry:register` against the standing registry; incompatible changes block merge. |

## Modules

| Module | Role |
|--------|------|
| `schema-gen-tools` | Build-only schema generator (victools). Never on service classpath. |
| `schema-messaging-core` | Domain-agnostic messaging library (converter, resolver, schema-resolution wiring). No AMQP topology — see `amqp-topology-kit`. |
| `amqp-topology-kit` | Domain-agnostic AMQP topology-building library (naming conventions, retry-ladder factory). Depended on only by `order-contracts` / `customer-contracts`. |
| `producer-service` / `consumer-service` | Spring Boot demo apps (:8081 / :8082). |

See `docs/adr/0001-code-first-schema-generation.md` for the code-first schema decision, and
`docs/adr/0002-contract-owned-amqp-topology.md` for the AMQP topology ownership reversal.
