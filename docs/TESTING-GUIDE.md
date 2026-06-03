# Step-by-Step Testing Guide — Schema Registry Demo

## Context
Phases 0–6 are fully implemented. This guide walks through every testing layer in dependency order:
build verification → unit tests → integration tests → manual end-to-end → failure topology →
schema governance → observability. Seed data (curl payloads) is included for every manual scenario.

---

## Prerequisites

Before starting, verify you have:
- **Java 25** JDK installed and a matching entry in `~/.m2/toolchains.xml`
- **Docker Desktop** running (needs ~4 GB RAM headroom for 5 containers)
- **Working directory:** `/Users/ahmadnaqibularefin/Projects/schema-registry-demo`

Verify toolchain entry exists:
```bash
grep -A4 "jdk" ~/.m2/toolchains.xml   # must show version 25
```

---

## Background: Apicurio Registry Primer

> Skip this if you already know what a schema registry is. Come back to it if something in a
> later step doesn't make sense.

**What is it?** Think of Apicurio Registry as **Git, but for message schemas**. Just like Git
stores every version of your code and lets you compare or roll back, Apicurio stores every
version of your message contracts and enforces that new versions don't break existing readers.

### The three-level hierarchy

Apicurio organises schemas in three levels: Group → Artifact → Version.

```
Group  (events.orders)
  └── Artifact  (OrderCreated)
        ├── Version 1  → globalId: N   ← original schema, immutable forever
        └── Version 2  → globalId: M   ← + optional promo_code field
```

| Concept | Value in this project | Plain-English meaning |
|---------|----------------------|----------------------|
| **Group** | `events.orders` / `events.customers` | Namespace — a folder for related schemas |
| **Artifact** | `OrderCreated` / `CustomerRegistered` | The schema itself |
| **Version** | `1`, `2`, … | An immutable snapshot; once stored it never changes |
| **Global ID** | e.g. `1`, `7`, `12` | A registry-wide unique number assigned to each version |
| **Compatibility rule** | `BACKWARD` | Policy stored in Apicurio; checked on every new registration |

### What "BACKWARD" means (one rule, one sentence)

> A **consumer** using the new schema can still read messages that were **produced** with the
> old schema.

In practice:
- **Allowed** — add an optional field (old messages simply don't have it; the consumer treats it as absent)
- **Rejected** — remove a field, rename a field, or add a *required* field (old messages would fail the new contract)

### Why is there a `schema-registrar` container?

On a cold start Apicurio has no schemas and no rules. The `schema-registrar` Docker service is
a one-shot Maven job that:
1. Registers v1 and v2 of both schemas
2. Attaches the `BACKWARD` rule to each artifact as a **standing policy**

Once attached, Apicurio checks every future registration against that policy automatically —
whether it comes from your laptop, from CI, or from a deployment pipeline.

---

## Step 1 — Start Infrastructure

```bash
docker compose up -d
```

Wait for all services to become healthy (~60 s on first run, image pulls may add time):

```bash
docker compose ps   # all STATUS columns should show "healthy" or "exited 0" (schema-registrar is a one-shot)
```

**Services and their UIs:**

| Service | Port | UI / Check |
|---|---|---|
| Apicurio Registry API | 8080 | `curl http://localhost:8080/apis/registry/v3/system/info` |
| Apicurio Registry UI | 8888 | http://localhost:8888 |
| RabbitMQ | 5672 | — |
| RabbitMQ Management | 15672 | http://localhost:15672 (guest/guest) |
| Jaeger UI | 16686 | http://localhost:16686 |
| Postgres | 5432 | — (backing store for Apicurio) |

The **schema-registrar** container is a one-shot Maven job that registers both schemas and attaches
BACKWARD compatibility rules. It exits 0 after success. If it exited non-zero, re-run with:
```bash
docker compose run --rm schema-registrar
```

Verify schemas are registered via API:
```bash
curl -s http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts | jq '.count'
curl -s http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts | jq '.count'
# Both should return at least 1 (likely 2 after evolution registration)
```

**Exploring the Apicurio UI (http://localhost:8888):**

This is the easiest way to understand what the registry has stored. Follow these clicks:

1. Open http://localhost:8888 → click **"Explore"** in the left nav
2. You see two groups: **`events.orders`** and **`events.customers`**
3. Click **`events.orders`** → click **`OrderCreated`**
   - You see **2 versions** listed with their Global IDs
   - Click **Version 1**: shows the original `.proto` content (fields 1–7, no `promo_code`)
   - Click **Version 2**: shows the evolved content (same fields + optional `promo_code` field 8)
   - Click the **"Rules"** tab: shows the `BACKWARD` compatibility rule attached to this artifact
4. Note the **Global ID** number next to each version. This exact number is what the producer
   stamps in the `X-Schema-GlobalId` header on every message it sends. The consumer reads that
   header to fetch the schema in a single call — no coordinate lookup needed.
5. Repeat for **`events.customers`** → **`CustomerRegistered`** to see the JSON Schema versions
   - Version 1: original schema (no `promoCode` property)
   - Version 2: adds optional `promoCode` (not in `required` array — BACKWARD-compatible)

---

## Step 2 — Full Build

```bash
./mvnw clean install -DskipTests
```

This compiles all 5 modules, generates Protobuf classes (order-contracts) and JSON Schema POJOs
(customer-contracts), and packages the Spring Boot fat jars. Expect ~90 s on a cold M-series Mac.

Fix any compilation failures before proceeding.

---

## Step 3 — Unit Tests (fast, no Docker needed)

```bash
./mvnw test
```

Expected: **all tests green, zero failures.** Key suites:
- `schema-messaging-core` — 23+ tests (cache, converter, routing taxonomy, etc.)
- `order-contracts` — Protobuf round-trip + evolution tests
- `customer-contracts` — JSON round-trip + evolution tests
- `producer-service` — ProducerValidationTest (REST 400 on invalid payload)

Run a single targeted test to verify a specific scenario:
```bash
./mvnw -pl schema-messaging-core test -Dtest=SchemaResolverTest#cacheHit
./mvnw -pl schema-messaging-core test -Dtest=EventConsumerSupportTest
./mvnw -pl schema-messaging-core test -Dtest=SchemaAwareMessageConverterTest
```

---

## Step 4 — Integration Tests (Testcontainers — needs Docker)

```bash
./mvnw verify
```

This starts real RabbitMQ containers (via Testcontainers) per test class. The Apicurio client is
mocked so no running registry is needed. Expect ~3–5 min.

**6 integration test classes in consumer-service:**

| Class | What it proves |
|---|---|
| `OrderCreatedIT` | Protobuf round-trip E2E, all X-Schema-* headers correct, globalId fast path |
| `CustomerRegisteredIT` | JSON Schema round-trip E2E, backward-compat field tolerance |
| `DlxRoutingIT` | All 5 failure modes → correct DLQ/retry routing, X-Failure-* headers |
| `SchemaVersionPinningIT` | Pinned schema version appears in X-Schema-Version header |
| `StartupSchemaValidatorIT` | auto-register=OFF + dead registry → fail-fast on startup |
| `PrometheusMetricsIT` | /actuator/prometheus exposes all schema.* meters |

Run a single integration test class:
```bash
./mvnw -pl consumer-service verify -Dit.test=DlxRoutingIT
```

---

## Step 5 — Start the Services (Manual E2E)

Open **two terminal tabs**. Infrastructure from Step 1 must still be running.

**Terminal 1 — Consumer:**
```bash
./mvnw -pl consumer-service spring-boot:run
```
Wait for: `Started ConsumerApplication` and `Pre-warmed N schemas from Apicurio registry`.

**Terminal 2 — Producer:**
```bash
./mvnw -pl producer-service spring-boot:run
```
Wait for: `Started ProducerApplication`.

Service ports: producer = **8081**, consumer = **8082**.

---

## Step 6 — Happy Path: Publish Events

### 6a. Publish an Order (Protobuf)

```bash
curl -s -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-001",
    "productId": "prod-456",
    "quantity": 2,
    "totalAmount": 49.99,
    "currency": "USD"
  }' | jq .
```

**Expected:** HTTP 201 response JSON with the orderId.

**What to observe:**
- Producer logs: `INFO … Published OrderCreated orderId=<id>` (with X-Message-Id and X-Correlation-Id)
- Consumer logs: `INFO … Received OrderCreated orderId=<id>`
- RabbitMQ UI → Queues → `orders.created.queue` — message count briefly spikes then drops to 0

### 6b. Publish a Customer (JSON Schema)

```bash
curl -s -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@example.com",
    "firstName": "Alice",
    "lastName": "Smith",
    "phoneNumber": "+12025550123"
  }' | jq .
```

**Expected:** HTTP 201, consumer logs `Received CustomerRegistered customerId=<id>`.

### 6c. Publish multiple to see throughput

```bash
for i in {1..5}; do
  curl -s -X POST http://localhost:8081/api/orders \
    -H "Content-Type: application/json" \
    -d "{\"customerId\":\"cust-00$i\",\"productId\":\"prod-$i\",\"quantity\":$i,\"totalAmount\":$((i*10)).00,\"currency\":\"USD\"}" \
    | jq -r '.orderId'
done
```

---

## Step 7 — Validation Failure (Producer Returns 400)

### 7a. Invalid Order (missing required field)

The `quantity` field must be positive; `currency` is required. Send an incomplete payload:

```bash
curl -s -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-bad"
  }' | jq .
```

**Expected:** HTTP 400 with body `{"error":"Schema validation failed: …"}`.
**Key:** No message is emitted to RabbitMQ — the producer validates before publishing.

### 7b. Invalid Customer (missing required `email`)

```bash
curl -s -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Bob",
    "lastName": "Jones"
  }' | jq .
```

**Expected:** HTTP 400. Consumer receives nothing. DLQs remain empty.

---

## Step 8 — Poison Message / DLQ Demo

### 8a. Send a poison message (invalid protobuf bytes, valid headers)

```bash
curl -s -X POST http://localhost:8081/api/orders/poison \
  -H "Content-Type: application/json" \
  | jq .
```

**Expected:** HTTP 202 (accepted for sending). This bypasses schema validation on the producer side
and emits garbage protobuf bytes with valid X-Schema-* headers.

**What to observe:**
1. Consumer logs: `ERROR … DeserializationException` — permanent failure → DLQ_DIRECT (no retry)
2. RabbitMQ UI → `orders.created.dlq` — message count = 1
3. In RabbitMQ UI → Get Messages on `orders.created.dlq`, headers should contain:
   - `X-Failure-Reason: PERMANENT`
   - `X-Failure-Message: … DeserializationException …`
   - `X-Failure-Retry-Count: 0` (not retried at all)
   - `X-Failure-Stack-Trace` (truncated to 4 KB)
   - `X-Failure-Original-Routing-Key: orders.created`

### 8b. Verify retry TTL ladder (transient failure simulation)

The retry ladder (5 s → 30 s → 5 min → DLQ) is tested automatically in `DlxRoutingIT` with
compressed TTLs (200 ms / 400 ms / 600 ms). To see it live, temporarily stop the Apicurio
container while a message is in-flight:

```bash
docker compose stop apicurio-registry
# Then publish an order
curl -s -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"cust-retry","productId":"prod-r","quantity":1,"totalAmount":9.99,"currency":"USD"}'
# Observe: consumer retries 3 times (5s, 30s, 5m TTLs) then message lands in DLQ
docker compose start apicurio-registry
```

---

## Step 9 — Schema Governance: Compatibility Gate

> **What is Apicurio actually checking?**
>
> When you register (or test) a new schema version, Apicurio compares it against the
> previously stored versions using the artifact's `BACKWARD` compatibility rule. The question
> it answers is:
>
> *"Can a consumer that was built against the new schema still correctly read a message that
> was produced with the old schema?"*
>
> For **Protobuf**: safe to add optional fields at the end (field numbers are preserved, old
> messages simply have the new field absent). Unsafe: change a field's type or number, remove
> a field.
>
> For **JSON Schema**: safe to add new properties that are not listed in `required` (old
> messages without that property still validate). Unsafe: add a new required field, remove an
> existing required field, or change a type.

### 9a. Test that the registered schemas are BACKWARD-compatible

```bash
./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected:** Both goals succeed (exit 0) — current schemas are compatible.

**Note:** `compat-check` runs in **dryRun mode** — it asks Apicurio "would this schema pass?",
but never stores anything. The version count in the UI stays the same before and after.

### 9b. Attempt to register an INCOMPATIBLE schema (should fail)

The `order-contracts` POM has an `incompatible-demo` profile that registers
`order-created-incompatible.proto` (changes field 1 from `string` to `int64`):

```bash
./mvnw -pl order-contracts verify \
  -Pincompatible-demo \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected:** Maven goal FAILS with `INCOMPATIBLE` from Apicurio. This is the CI merge gate in
action — an incompatible schema change would block the PR.

**What you'll see in the Maven output:** a message like `RuleViolationException: INCOMPATIBLE`.
This error comes directly from Apicurio's compatibility API — Maven is just surfacing it. The
incompatible schema is **never stored**; the UI version count remains unchanged.

Same for JSON Schema (adds new required field `accountType` — old messages don't have it, so old data fails the new schema):
```bash
./mvnw -pl customer-contracts verify \
  -Pincompatible-demo \
  -Dapicurio.registry.url=http://localhost:8080
```

### 9c. Schema versioning lifecycle walkthrough (how to add a v3 yourself)

This is a hands-on exercise that ties together everything you've learned. It takes about
5 minutes and shows the full loop from code change → registry → running producer.

**Step 1** — Add a new optional field to the Protobuf schema:
```proto
// order-contracts/src/main/resources/schemas/order-created.proto
// Add this line after field 8:
optional string notes = 9;
```

**Step 2** — Check compatibility *before* registering (dryRun, safe to run):
```bash
./mvnw -pl order-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
```
This should **pass** — adding an optional field is BACKWARD-compatible.

**Step 3** — Register the new version:
```bash
./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
```
Apicurio creates **Version 3** and assigns it a new Global ID.

**Step 4** — Verify in the UI:
Open http://localhost:8888 → `events.orders` → `OrderCreated` → you now see **3 versions**.
Version 3 shows your new `notes` field. The Global ID for version 3 is different from versions 1 and 2.

**Step 5** — Restart the producer (picks up the new "latest" schema):
```bash
# Ctrl+C the running producer, then:
./mvnw -pl producer-service spring-boot:run
```
After restart, new messages will carry `X-Schema-GlobalId: <version-3-id>` and
`X-Schema-Version: 3` in their headers.

**Step 6** — Confirm backward compatibility is preserved:
The consumer still processes v1 and v2 messages correctly — old messages simply don't have
the `notes` field and it defaults to absent/empty.

> **Key insight:** You never had to touch the consumer to add this field. That's the point of
> BACKWARD compatibility — producers can evolve independently as long as they follow the rules.

---

## Step 10 — Schema Version Pinning

Edit `producer-service/src/main/resources/application.yml` locally (or pass as env var):
```yaml
schema:
  orders:
    pinned-version: "1"
```

Restart the producer and publish an order. Observe in consumer logs that `X-Schema-Version: 1`
(not "latest"). To reset, remove or set `pinned-version: "latest"`.

---

## Step 11 — Observability

### 11a. Metrics (Prometheus / Actuator)

```bash
curl -s http://localhost:8081/actuator/prometheus | grep "^schema_"
curl -s http://localhost:8082/actuator/prometheus | grep "^schema_"
```

**Expected metrics (both services):**
```
schema_cache_hits_total
schema_cache_misses_total
schema_fetch_failures_total
schema_validation_failures_total
schema_publish_count_total
schema_consume_count_total
```

### 11b. Health

```bash
curl -s http://localhost:8081/actuator/health | jq .
curl -s http://localhost:8082/actuator/health | jq .
```

Producer should show `{"status":"UP"}` with `registry` and `rabbit` components.
Consumer should show `{"status":"UP"}` with `registry`, `rabbit`, and `queueDepth` components.

**Note:** If you ran Step 8 (poison message demo) before this step, the consumer's `queueDepth`
component will report `DOWN` — that is correct, intentional behaviour: the indicator signals DOWN
whenever a DLQ is non-empty. To reset before re-checking health, purge the DLQ via the RabbitMQ
Management UI (http://localhost:15672) → Queues → `orders.created.dlq` → Purge Messages.

### 11c. Distributed Tracing (Jaeger)

Open http://localhost:16686 → select service `producer-service` or `consumer-service` →
Find Traces. After publishing events (Step 6), produce→consume spans should appear with matching
`X-Correlation-Id` / traceId linking them.

---

## Step 12 — Schema Evolution: Register v2 and Test Backward Compatibility

Both schemas already have a v2 registered by the `schema-registrar` container (Protobuf v2 adds
optional `promo_code`; JSON v2 adds optional `promoCode`). To observe backward compatibility:

```bash
# Publish a v1 order (no promo_code) — consumer reads it fine, promo_code is empty/absent
curl -s -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-evo","productId":"prod-evo","quantity":1,"totalAmount":19.99,"currency":"EUR"}'

# Check Apicurio UI for both versions (http://localhost:8888) under events.orders / OrderCreated
```

The registry shows 2 versions of each artifact. Consumers reading v1 messages with v2 code
silently ignore the new optional field.

---

## Step 13 — Teardown

```bash
# Stop services (Ctrl+C in each terminal)

# Stop infrastructure
docker compose down

# To also remove volumes (wipes Postgres data — schemas must be re-registered next time)
docker compose down -v
```

---

## Verification Checklist

| Scenario | Pass Criterion |
|---|---|
| `./mvnw test` | Zero failures, all 48+ unit tests green |
| `./mvnw verify` | All 6 IT classes green (OrderCreatedIT, CustomerRegisteredIT, DlxRoutingIT, etc.) |
| POST /api/orders (valid) | HTTP 201, consumer logs receipt, queue depth returns to 0 |
| POST /api/orders (invalid) | HTTP 400, no message emitted |
| POST /api/orders/poison | HTTP 202, message lands in `orders.created.dlq` immediately (X-Failure-Retry-Count: 0) |
| `verify -Pcompat-check` (compatible) | Maven goal succeeds |
| `verify -Pincompatible-demo` | Maven goal FAILS with INCOMPATIBLE error |
| `/actuator/prometheus` | All `schema_*` meters present |
| `/actuator/health` | status UP with registry component |
| Jaeger UI | Produce→consume spans visible, linked by traceId |

---

## Quick Reference — Seed Data

### Valid Order
```json
{"customerId":"cust-001","productId":"prod-456","quantity":2,"totalAmount":49.99,"currency":"USD"}
```

### Valid Customer
```json
{"email":"alice@example.com","firstName":"Alice","lastName":"Smith","phoneNumber":"+12025550123"}
```

### Invalid Order (triggers 400)
```json
{"customerId":"cust-bad"}
```

### Invalid Customer (triggers 400 — missing email)
```json
{"firstName":"Bob","lastName":"Jones"}
```
