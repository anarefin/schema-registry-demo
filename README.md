# Schema Registry POC — Apicurio + RabbitMQ + Spring Boot 4.0

End-to-end schema-governed messaging: **Apicurio Registry 3.2.0** as schema source of truth,
**RabbitMQ** as transport, two **Spring Boot 4.0** services on **Java 25**. Demonstrates JSON
Schema message flows (orders + customers), schema-compatibility governance as a CI merge gate
(FORWARD — see §2 for why JSON Schema artifacts use FORWARD), and a full DLX/DLQ/retry failure
topology.

---

## What this POC proves

| Spec §18 criterion | Demonstrated by |
|---|---|
| Cold `compose up` → all services healthy | `docker compose up`, healthchecks |
| Orders + customers (both JSON Schema) received & deserialized | Demo curls → consumer logs |
| Two artifacts with ≥2 versions, FORWARD compat rule on both | `apicurio-registry:register` (v1 + v2) |
| Incompatible v3 rejected with clear error | `verify -Pincompatible-demo` |
| Malformed payload → correct DLQ, all `X-Failure-*` headers | `POST /api/orders/poison` |
| Registry down → cached processing, new messages fail gracefully | `SchemaResolver` last-known-good |
| `auto-register=OFF` + unregistered schema → fail to start | `StartupSchemaValidator` |
| README walkthrough on fresh clone in under 15 min | This file |

---

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 25 (entry in `~/.m2/toolchains.xml`, vendor `oracle`, `id` `25-oracle`) |
| Docker + Docker Compose | Compose v2 (`docker compose`) |
| Maven | Provided via `./mvnw` (Maven Wrapper — **always use `./mvnw` not `mvn`**) |

No system Maven installation needed. The wrapper downloads Maven 3.9.11 automatically.

---

## Quick start (≤ 15 min)

### 1. Build all modules

```bash
./mvnw clean install -DskipTests
```

Compiles all five modules, runs code generation (jsonschema2pojo), and installs
JARs into the local Maven repository. Skip tests for speed; run them later with `./mvnw verify`.

### 2. Start infrastructure

```bash
docker compose up
```

Starts (in dependency order, all with health checks):

| Service | Port(s) | Notes |
|---|---|---|
| `postgres` | 5432 | Apicurio SQL storage |
| `apicurio` | 8080 | Registry API |
| `apicurio-ui` | 8888 | Registry UI |
| `rabbitmq` | 5672 / 15672 | AMQP + management UI |

Schema registration is **not** a compose service — it's a host-Maven step. The contracts modules
already carry the `apicurio-registry-maven-plugin`, so once Apicurio is healthy you register both
schemas and attach their compatibility rules directly from the host.

Both artifacts are JSON Schema and use the **FORWARD** compatibility level — in Apicurio's
JSON Schema checker, adding a property (even an optional/permissive one) is classified as
`OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED` and is rejected under BACKWARD; it is only valid
under FORWARD.

```bash
# 1. Register all schemas (order + customer: v1 + v2; payment: v1)
./mvnw -pl order-contracts,customer-contracts,payment-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080

# 2. Attach the compatibility rules (register does not do this)
curl -s -o /dev/null -X POST \
  "http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
curl -s -o /dev/null -X POST \
  "http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
curl -s -o /dev/null -X POST \
  "http://localhost:8080/apis/registry/v3/groups/events.payments/artifacts/PaymentProcessed/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
```

Step 2 can also be run via the **Schema Governance Bootstrap** GitHub workflow
(`.github/workflows/schema-governance-bootstrap.yml`).

### 3. Start the services

Two separate terminals:

```bash
# Terminal 1 — producer (port 8081)
./mvnw -pl producer-service spring-boot:run

# Terminal 2 — consumer (port 8082)
./mvnw -pl consumer-service spring-boot:run
```

### 4. Demo: publish messages

```bash
# Publish an order event (JSON Schema → events.orders)
curl -s -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-1","productId":"prod-42","quantity":2,"totalAmount":99.99,"currency":"USD"}'

# Publish a customer event (JSON Schema → events.customers)
curl -s -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","firstName":"Alice","lastName":"Smith","phoneNumber":"+15551234567"}'
```

Consumer logs confirm receipt and full deserialization. Check `X-Schema-*` headers in RabbitMQ
management UI → queues → `orders.created.queue` or `customers.registered.queue`.

### 5. Demo: schema validation failure (producer-side)

Publisher validates before sending. Missing required field returns `400`; no message is emitted.

```bash
# Missing required firstName / lastName → 400 Bad Request, nothing published
curl -s -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{"email":"bad@example.com"}'
```

### 6. Demo: malformed payload → DLQ

```bash
# Publishes garbage JSON bytes with valid X-Schema-* headers
curl -s -X POST http://localhost:8081/api/orders/poison
```

Consumer fails schema validation (unparseable JSON) → `SchemaValidationException` → **no retry** → `orders.created.dlq`.
In RabbitMQ management UI (http://localhost:15672, guest/guest) browse `orders.created.dlq` to
inspect all `X-Failure-*` headers (reason, message, stack trace truncated to 4 KB, original
routing key, failed-at, retry-count).

### 7. Demo: schema evolution — accept and reject

**Accepted:** add an optional property — FORWARD-compatible for both artifacts
(`promoCode` etc. on `OrderCreated`, `promoCode`/`input1` on `CustomerRegistered`).
Apicurio rejects JSON property additions under BACKWARD, which is why both artifacts
use a FORWARD rule.

```bash
# Register v2 (optional promoCode etc. already present in order-created.json)
./mvnw -pl order-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080

# ≥2 versions visible in Apicurio UI → events.orders / OrderCreated
```

**Rejected (incompatible):** changing an existing property's type (`quantity` integer → string)
violates every compatibility level.

```bash
# Dry-run incompatible schema — BUILD FAILURE with Apicurio rejection message
./mvnw -pl order-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080
```

Same CI gate for customers:

```bash
./mvnw -pl customer-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080
```

---

## Inspecting the system

| UI / endpoint | URL | Credentials |
|---|---|---|
| Apicurio Registry UI | http://localhost:8888 | none |
| RabbitMQ management | http://localhost:15672 | guest / guest |
| Producer health | http://localhost:8081/actuator/health | none |
| Consumer health | http://localhost:8082/actuator/health | none |

---

## Sequence diagrams

### Produce

```mermaid
sequenceDiagram
    participant C as Client
    participant P as ProducerService
    participant R as SchemaResolver
    participant A as ApicurioRegistry
    participant MQ as RabbitMQ

    C->>P: POST /api/orders {payload}
    P->>P: build OrderCreated (JSON)
    P->>R: resolve(SchemaCoordinates)
    R->>A: GET /apis/registry/v3/groups/events.orders/artifacts/OrderCreated
    A-->>R: schema bytes
    R-->>P: ResolvedSchema (cached)
    P->>P: validate + serialize (raw JSON bytes)
    P->>MQ: publish to events.exchange<br/>X-Schema-GlobalId / X-Schema-Type / X-Correlation-Id
    P-->>C: 201 Created
```

### Consume

```mermaid
sequenceDiagram
    participant MQ as RabbitMQ
    participant CS as ConsumerService
    participant R as SchemaResolver
    participant A as ApicurioRegistry

    MQ->>CS: deliver message (JSON bytes + X-Schema-* headers)
    CS->>R: fetchByGlobalId(X-Schema-GlobalId)
    alt cache hit
        R-->>CS: ResolvedSchema (from Caffeine cache)
    else cache miss
        R->>A: GET /apis/registry/v3/ids/globalIds/{id}
        A-->>R: schema bytes
        R-->>CS: ResolvedSchema (now cached)
    end
    CS->>CS: deserialize → OrderCreated
    CS->>CS: business logic (OrderEventListener)
```

### Evolution rejected

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant CI as CI Pipeline
    participant A as ApicurioRegistry

    Dev->>CI: push incompatible schema change
    CI->>A: apicurio-registry:register -DdryRun (compatibility check)
    A-->>CI: 409 Conflict — compatibility rule violated (FORWARD)
    CI-->>Dev: BUILD FAILURE (clear rejection message)
    Note over Dev,A: Merge blocked — incompatible change never lands
```

### Validation failed

```mermaid
sequenceDiagram
    participant C as Client
    participant P as ProducerService
    participant A as ApicurioRegistry

    C->>P: POST /api/customers {missing required fields}
    P->>A: resolve schema
    A-->>P: JSON Schema
    P->>P: networknt validate → FAIL
    P->>P: throw SchemaValidationException
    P-->>C: 400 Bad Request (no message emitted)
    Note over P,A: RabbitMQ never sees the message
```

---

## Running the test suite

```bash
./mvnw test           # unit tests only (fast, no Docker)
./mvnw verify         # unit + Testcontainers integration tests
./mvnw -pl schema-messaging-core test   # single-module
./mvnw -pl schema-messaging-core test -Dtest=SchemaResolverTest#cacheHit   # single test
```

Tests are split by convention: `*Test.java` → Surefire (unit, mock-based);
`*IT.java` → Failsafe (Testcontainers, real RabbitMQ + Apicurio). Keep new tests on the
correct side — the split is load-bearing.

---

## POC-only shortcuts

The following design decisions are intentional simplifications for a proof-of-concept.
They are not appropriate for production use as-is.

| Shortcut | POC rationale | Production path |
|---|---|---|
| No consumer-side deduplication (honest at-least-once delivery) | Keeps the consumer stateless; no distributed dedup store | Redis / database deduplication store keyed on a producer-supplied message id |
| Single-instance Apicurio Registry (no HA) | Simplifies compose topology | Multi-node Apicurio behind a load balancer, connection pooling |
| Cache TTL vs evolution latency | 300 s `refresh-after-write` means producers see new schemas within 5 min | Tune or use event-driven cache invalidation (registry webhooks) |
| OIDC disabled by default | No Keycloak setup needed for the demo | Enable via `RegistryClientOptions.oauth2(...)` in `SchemaMessagingAutoConfiguration` — see Javadoc for Keycloak token-url pattern |
| Schema registration is a manual host-Maven step after `docker compose up` | Keeps the build single-source (no second Maven toolchain in a container) | CI: run `apicurio-registry:register` + rule attachment as a dedicated post-deploy Maven step with a populated cache layer |
| `apicurio.auto-register=ON` (default) | Services start without pre-registered schemas | Set `OFF` in production; `StartupSchemaValidator` then fails fast if a pinned schema is missing |

---

## Maven command reference

```bash
# Build
./mvnw clean install                               # full build + local install
./mvnw clean install -DskipTests                   # skip all tests
./mvnw -pl schema-messaging-core compile           # compile one module

# Test
./mvnw test                                        # unit tests (Surefire, *Test.java)
./mvnw verify                                      # unit + IT (Failsafe, *IT.java)
./mvnw -pl consumer-service verify                 # IT for one module

# Schema governance (requires docker compose up)
./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080

./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
       -Dapicurio.registry.url=http://localhost:8080

# Incompatible change dry-run (CI gate demo)
./mvnw -pl order-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl customer-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080

# Run services
./mvnw -pl producer-service spring-boot:run        # port 8081
./mvnw -pl consumer-service spring-boot:run        # port 8082

# Run with prod profile (structured JSON logging)
./mvnw -pl producer-service spring-boot:run -Dspring-boot.run.profiles=prod

# Version pinning demo (resolves exact schema version at startup)
SCHEMA_ORDERS_PINNED_VERSION=1 ./mvnw -pl producer-service spring-boot:run
```

---

## Gradle migration (deferred)

The POC uses Maven by design: `apicurio-registry-maven-plugin` provides first-class
`register` and `test` goals with no trusted Gradle equivalent. Migrating the build to Gradle
is explicitly out of scope for this POC and tracked as a future epic (spec §0.4).
