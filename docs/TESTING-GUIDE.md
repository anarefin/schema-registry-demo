# Step-by-Step Testing Guide — Schema Registry Demo

## 0. Purpose & how to use this guide

`README.md` is the 15-minute quick start: build, start infra, register schemas, run the demo
curls. This guide goes deeper — it walks **every feature** of the POC one at a time, states the
exact expected output/assertion for each step, and covers scenarios the README intentionally
skips for brevity (the retry ladder, DLQ health, startup fail-fast for missing classpath schemas,
and a structural walkthrough of the four CI workflows).

Runtime validation uses classpath schemas via `LocalSchemaCatalog` (ADR-0004) — producer/consumer
never call Apicurio at runtime. Apicurio remains the CI/governance tool (register + compat-check).

Work through the sections in order — later sections (evolution, CI) assume the infrastructure and
services from earlier sections are already up. All commands assume you're running from the repo
root with `./mvnw` (the Maven Wrapper — never a system `mvn`).

Six event types flow through this system, three per domain:

| Domain | Events | Routing keys |
|---|---|---|
| `events.orders` | `OrderCreated`, `OrderShipped`, `OrderCancelled` | `orders.created`, `orders.shipped`, `orders.cancelled` |
| `events.customers` | `CustomerRegistered`, `CustomerAddressAdded`, `CustomerTierChanged` | `customers.registered`, `customers.address-added`, `customers.tier-changed` |

---

## 1. Prerequisites

| Tool | Requirement |
|---|---|
| JDK | 25, with a `~/.m2/toolchains.xml` entry (`vendor=oracle`, `id=25-oracle`) |
| Docker | Compose v2 (`docker compose`, not the legacy `docker-compose`) |
| Maven | None needed system-wide — always use the committed wrapper `./mvnw` |

```bash
./mvnw -v   # confirm the wrapper resolves Maven 3.9.11 and picks up the Java 25 toolchain
```

---

## 2. Build & unit tests

```bash
./mvnw clean install -DskipTests
```

Builds all six runtime/library modules (`schema-messaging-core`, `amqp-topology-kit`,
`order-contracts`, `customer-contracts`, `producer-service`, `consumer-service`, plus the
build-only `schema-gen-tools`, seven total) and, as part of
`schema-gen-tools`' `process-classes` phase, regenerates all six JSON Schemas from the code-first
records. Expect `BUILD SUCCESS`.

```bash
./mvnw test
```

Runs only Surefire (`*Test.java`) — fast, mock-based, no Docker. Expect `BUILD SUCCESS` with test
counts across `schema-messaging-core`, `order-contracts`, `customer-contracts`, and
`producer-service`.

**What you verified:** the code-first records compile, schemas regenerate without error, and all
mock-based unit tests pass. The `*Test.java` (Surefire) / `*IT.java` (Failsafe) split is
load-bearing — don't put a Testcontainers test under `*Test.java` or it'll silently run twice (once
here, once in §5) or not at all.

---

## 3. Schema generation & drift gate (offline — no registry needed)

This is exactly what `.github/workflows/schema-drift-check.yml` runs on every PR touching
`*-contracts/**` or `schema-gen-tools/**`, and you can run it locally with no infrastructure:

```bash
./mvnw -pl order-contracts,customer-contracts -am process-classes
git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'
```

Expect no diff (exit code `0`) — the committed `.schema.json` files are byte-identical to what
`schema-gen-tools` (victools, Draft-07, alphabetically-sorted keys, fixed pretty-printer) just
regenerated.

**Now deliberately cause drift** to see the gate catch a real mismatch:

```bash
# Add a comment/description-only edit to a record, e.g. tweak the @JsonPropertyDescription
# on OrderCreated.quantity() in order-contracts, then:
./mvnw -pl order-contracts -am process-classes
git diff -- '*-contracts/src/main/resources/schemas/order-created.schema.json'   # see the diff
git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'               # now exits 1

git checkout -- order-contracts/src/main/java/com/example/contracts/orders/OrderCreated.java
./mvnw -pl order-contracts -am process-classes   # regenerate back to the committed baseline
git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'               # back to exit 0
```

**What you verified:** schema generation is deterministic, and any drift between a Java record and
its committed schema is mechanically detectable without touching the registry.

---

## 4. Integration tests (Testcontainers)

```bash
./mvnw verify
```

Runs Failsafe (`*IT.java`) on top of everything in §2 — spins up real RabbitMQ (Testcontainers).
Schema validation is local (`LocalSchemaCatalog`); no live registry is required for ITs. Expect
`BUILD SUCCESS`. What each class proves:

| Test class | Proves |
|---|---|
| `OrderCreatedIT` | Producer → real RabbitMQ → consumer round trip for `OrderCreated`; asserts `content-type: application/json` and `X-Schema-GroupId` / `ArtifactId` / `Type` (+ correlation id) on the delivered message. |
| `CustomerRegisteredIT` | Same round trip for `CustomerRegistered`; also verifies an extra unknown JSON field on the wire still deserializes (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`). |
| `DlxRoutingIT` | Retry-ladder + DLQ matrix: deserialization poison / missing schema headers / unknown artifact → immediate DLQ (`X-Retry-Count=0`); downstream `RuntimeException` → retried 3× then DLQ; all `X-Failure-*` headers present on final DLQ. |
| `LocalSchemaCatalogStartupIT` | A `TypeMapping` whose classpath schema resource is missing aborts Spring context refresh with `SchemaNotFoundException`. |

Run a single IT: `./mvnw -pl consumer-service verify -Dit.test=DlxRoutingIT`.

---

## 5. Start infrastructure

```bash
docker compose up
```

Wait for all four containers to report healthy:

```bash
docker compose ps
```

| Service | Port(s) | Health check |
|---|---|---|
| `postgres` | 5432 | `pg_isready` |
| `apicurio` | 8080 (Registry API) | `GET /apis/registry/v3/system/info` (60s start period, 30 retries — Postgres-backed storage takes a moment to initialize) |
| `apicurio-ui` | 8888 → container 8080 | `GET /` |
| `rabbitmq` | 5672 (AMQP), 15672 (management UI) | `rabbitmq-diagnostics ping` |

> **Doc note:** `docker-compose.yml`'s inline comment says "BACKWARD for OrderCreated, FORWARD for
> CustomerRegistered" — this is stale. All six artifacts actually use **FORWARD** (see §6 for why).

**What you verified:** cold `compose up` reaches an all-healthy state with no manual intervention
beyond waiting on health checks.

---

## 6. Register schemas & attach compatibility rules

Schema registration is a **host-Maven step**, not a compose service — both contract modules already
carry the `apicurio-registry-maven-plugin`.

```bash
./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080
```

Then attach the **FORWARD** compatibility rule to all six artifacts (register does not do this
itself):

```bash
for pair in \
  events.orders/OrderCreated \
  events.orders/OrderShipped \
  events.orders/OrderCancelled \
  events.customers/CustomerRegistered \
  events.customers/CustomerAddressAdded \
  events.customers/CustomerTierChanged; do
  group="${pair%/*}" artifact="${pair#*/}"
  curl -s -o /dev/null -X POST \
    "http://localhost:8080/apis/registry/v3/groups/${group}/artifacts/${artifact}/rules" \
    -H 'Content-Type: application/json' \
    -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
done
```

Why FORWARD and not BACKWARD: in Apicurio's JSON Schema checker, adding a property — even an
optional one — is classified `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`, which BACKWARD rejects and
only FORWARD accepts. This is the correct level for a schema-evolution strategy built around
additive optional fields.

Both steps can also be run as one shot via the **Schema Governance Bootstrap** workflow
(`.github/workflows/schema-governance-bootstrap.yml`, `workflow_dispatch` — see §17).

**Verify in the Apicurio UI** (http://localhost:8888):
- Groups `events.orders` and `events.customers` each show three artifacts.
- Each artifact's **Rules** tab shows `COMPATIBILITY = FORWARD`.

**What you verified:** all six code-first schemas are registered under their correct group/artifact
coordinates, and the FORWARD rule that gates future evolution (§15–§16) is active.

---

## 7. Start producer & consumer services

Two terminals:

```bash
# Terminal 1 — producer (port 8081)
./mvnw -pl producer-service spring-boot:run

# Terminal 2 — consumer (port 8082)
./mvnw -pl consumer-service spring-boot:run
```

Verify health on both:

```bash
curl -s localhost:8081/actuator/health | jq
curl -s localhost:8082/actuator/health | jq
```

With `show-details: always` and `show-components: always`, the consumer shows a
`components.queueDepth` entry (from `QueueDepthHealthIndicator`) with `status: UP` (no DLQ has
messages yet). There is no runtime registry health component — Apicurio is CI-only (ADR-0004).

**What you verified:** both services start cleanly against RabbitMQ (and a live registry is only
needed later for governance curls), and queue-depth health is wired before you send any traffic.

---

## 8. Happy path — publish all six events

Each curl maps a request DTO to the code-first record; `SchemaAwareMessageConverter` validates
against the resolved schema before the message is sent. Every call should return `201 Created`.

```bash
# --- Orders (events.orders) ---
curl -si -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111","productId":"22222222-2222-2222-2222-222222222222","quantity":2,"totalAmount":99.99,"currency":"USD"}'

curl -si -X POST http://localhost:8081/api/orders/ship \
  -H "Content-Type: application/json" \
  -d '{"orderId":"33333333-3333-3333-3333-333333333333","trackingNumber":"1Z999AA10123456784","carrier":"UPS"}'

curl -si -X POST http://localhost:8081/api/orders/cancel \
  -H "Content-Type: application/json" \
  -d '{"orderId":"33333333-3333-3333-3333-333333333333","reason":"Customer request","refundAmount":49.99}'

# --- Customers (events.customers) ---
curl -si -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","firstName":"Alice","lastName":"Smith","phoneNumber":"+15551234567"}'

curl -si -X POST http://localhost:8081/api/customers/address \
  -H "Content-Type: application/json" \
  -d '{"customerId":"44444444-4444-4444-4444-444444444444","address":{"line1":"221B Baker Street","city":"London","postalCode":"NW1 6XE","countryCode":"GB"}}'

curl -si -X POST http://localhost:8081/api/customers/tier \
  -H "Content-Type: application/json" \
  -d '{"customerId":"44444444-4444-4444-4444-444444444444","previousTier":"BRONZE","newTier":"GOLD"}'
```

For each: confirm `201 Created`, then confirm a matching `INFO` log line in the consumer terminal
(`OrderEventListener`/`CustomerEventListener` log every field of the typed record it deserialized).

**Inspect the wire format** in the RabbitMQ management UI (http://localhost:15672, `guest`/`guest`)
→ Queues → e.g. `orders.created.queue` → Get messages (with "Requeue" unchecked if you want to
consume it, or leave the consumer running and just watch the queue's message-rate graph blip). The
message properties should show:

| Header | Example value |
|---|---|
| `X-Schema-GroupId` | `events.orders` |
| `X-Schema-ArtifactId` | `OrderCreated` |
| `X-Schema-Type` | `JSON` |
| `X-Correlation-Id` | a UUID |
| content-type | `application/json` |

(No `X-Schema-GlobalId` / `X-Schema-Version` — runtime identity is group + artifact only, ADR-0004.)

Body is the **raw JSON only** — no envelope wrapper.

**What you verified:** all six event types round-trip end to end with the correct schema identity
headers and no envelope, matching the wire-format spec.

---

## 9. Validation failure (producer-side, 400)

The publisher validates against the resolved JSON Schema **before** sending. A payload missing
required fields never reaches RabbitMQ:

```bash
curl -si -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{"email":"bad@example.com"}'
```

Expect `400 Bad Request` with a body starting `Schema validation failed: ...` (from
`GlobalExceptionHandler` catching `SchemaValidationException`). Confirm in the management UI that
`customers.registered.queue`'s message count did **not** increase.

**What you verified:** schema validation is enforced on the producer side, and validation failure
is a hard stop — no partial/invalid message is ever published.

---

## 10. Poison message → DLQ (permanent failure, no retry)

```bash
curl -si -X POST http://localhost:8081/api/orders/poison
```

`OrderController` bypasses `EventPublisher` entirely here: it sends raw bytes `"{NOT_VALID_JSON"`
directly via `RabbitTemplate`, with **valid** `X-Schema-GroupId`/`ArtifactId`/`Type` headers on
routing key `orders.created`. Expect `202 Accepted`.

The consumer fails to parse the JSON before it can even validate → `DeserializationException`,
which is in the permanent-exception set → routed straight to `orders.created.dlq`, **no retry
hop**. In the management UI, browse `orders.created.dlq` and inspect the message headers:

| Header | Expected value |
|---|---|
| `X-Failure-Reason` | `DLQ_DIRECT` |
| `X-Failure-Message` | the deserialization error, truncated to 512 bytes |
| `X-Failure-StackTrace` | truncated to 4096 bytes (`...[truncated]` suffix if cut) |
| `X-Failure-Original-Routing-Key` | `orders.created` |
| `X-Failure-Failed-At` | an ISO-8601 instant |
| `X-Failure-Retry-Count` | `0` |

**What you verified:** a permanent failure (unparseable payload) never enters the retry ladder and
lands on the correct DLQ with the full failure-header set populated.

---

## 11. Transient failure → retry ladder → DLQ

Not covered in the README — this walks a message through all three retry tiers before it finally
DLQs. Schema presence is guaranteed at startup (`LocalSchemaCatalog`), so there is no runtime
"schema unavailable" path. Transient failures are **downstream handler errors** (any exception not
in the permanent set).

The reliable way to observe the consumer-side retry ladder is `DlxRoutingIT` (§4) — it spies the
listener to throw `RuntimeException` and asserts 4 deliveries then DLQ. Manually, once a transient
failure is triggered, watch the message hop through these queues in the management UI:

```
orders.created.queue  →  orders.created.retry.5s  →  orders.created.retry.30s  →  orders.created.retry.5m  →  orders.created.dlq
```

(Retry queue names have **no** `.queue` suffix — they're named directly `<routingKey>.retry.<tier>`.)
Exact TTLs: **tier 0 = 5000ms (5s)**, **tier 1 = 30000ms (30s)**, **tier 2 = 300000ms (5m)**
(`events.retry.tier0.ms`/`tier1.ms`/`tier2.ms`). Each retry queue dead-letters back into that
event's domain exchange with the *original* routing key on TTL expiry; `X-Retry-Count` increments
(`0`→`1`→`2`→`3`). Once `X-Retry-Count` reaches 3, the next failure is forced to `DLQ_DIRECT` and
`X-Failure-*` headers are populated (only on final DLQ arrival).

**What you verified:** transient failures are retried on a TTL ladder rather than DLQ'd immediately,
retry exhaustion still lands on the DLQ, and the queue-hop sequence matches the declared topology.

---

## 12. Startup fail-fast — missing classpath schema

Runtime schema pinning / Apicurio auto-register are gone (ADR-0004). Fail-fast is now: every
`TypeMapping` must have a matching `schemas/<kebab-name>.schema.json` on the classpath, or
`LocalSchemaCatalog` aborts context refresh.

Covered by `LocalSchemaCatalogStartupIT` (§4). Unit coverage also lives in
`LocalSchemaCatalogTest` / `JsonSchemaStrategyTest#warm_malformedSchema_throwsInvalidSchemaDefinition`.

**What you verified:** a missing or malformed classpath schema is a hard startup failure, not a
runtime surprise on the first message.

---

## 13. Schema evolution — accepted change

Add an optional field to `OrderCreated` (e.g. a nullable `notes` string with `@JsonPropertyDescription`,
no `@NotNull`), then:

```bash
./mvnw -pl order-contracts -am process-classes     # regenerate order-created.schema.json
git diff -- order-contracts/src/main/resources/schemas/order-created.schema.json   # see the new optional property

./mvnw -pl order-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080
```

Expect success — an additive optional property is FORWARD-compatible. Revert the record and schema
afterward (`git checkout --`) unless you intend to keep the change.

**What you verified:** the code-first workflow supports safe, additive schema evolution end to end —
edit the record, regenerate, register — with no manual schema authoring.

---

## 14. Schema evolution — rejected change

```bash
./mvnw -pl order-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080
```

This profile dry-run-registers a schema where `quantity` has changed type (integer → string) — a
change that violates every compatibility level, not just FORWARD. Expect `BUILD FAILURE` with
Apicurio's rejection message in the Maven output (a 409-style compatibility violation, not a
generic HTTP error).

Same gate for customers:

```bash
./mvnw -pl customer-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080
```

**What you verified:** a genuinely breaking change is rejected before it ever reaches a shared
branch, for both domains.

---

## 15. Compatibility gate — the same check CI runs

```bash
./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
       -Dapicurio.registry.url=http://localhost:8080
```

This is a **dry-run** register of exactly what `apicurio-registry:register` would publish (no
writes) — it's the identical Maven invocation `.github/workflows/schema-compat-check.yml` runs on
every PR touching `*-contracts/**`. With the working tree unchanged since §6, expect `BUILD SUCCESS`
(current schemas are compatible with themselves).

**What you verified:** the exact merge-gate command CI uses passes locally against your registered
baseline, before you ever open a PR.

---

## 16. CI governance workflows (structural walkthrough)

All four workflows discover contract modules dynamically (`for d in *-contracts; do [ -f "$d/pom.xml" ] && echo "$d"; done`), so adding a seventh domain module needs no workflow edit. Read each file in `.github/workflows/` alongside this table:

| Workflow | Trigger | Runner | What it runs | Pass/fail condition |
|---|---|---|---|---|
| `schema-compat-check.yml` | `pull_request`, paths `*-contracts/**` | `[self-hosted, apicurio-local]` (needs the standing registry at `localhost:8080`) | `./mvnw -pl <discovered> verify -Pcompat-check -Dapicurio.registry.url=...` | Fails if the dry-run registration is rejected as incompatible — same command as §16 |
| `schema-drift-check.yml` | `pull_request`, paths `*-contracts/**`, `schema-gen-tools/**` | `ubuntu-latest` (no registry needed) | regenerate via `schema-gen-tools`, then `git diff --exit-code` on `*-contracts/**/schemas/` | Fails on any byte drift — same command as §3 |
| `schema-governance-bootstrap.yml` | `workflow_dispatch` only (manual, optional `registry_url` input) | `[self-hosted, apicurio-local]` | re-registers all six artifacts, then POSTs `{"ruleType":"COMPATIBILITY","config":"FORWARD"}` to each of the six `/rules` endpoints (treats `200`/`204`/`409` as success), then re-`GET`s each artifact's rules to confirm | Fails if any rule-attach call returns an unexpected HTTP status — this is §6's manual steps, automated |
| `schema-register.yml` | `push` to `main`, paths `*-contracts/**` | `[self-hosted, apicurio-local]` | `apicurio-registry:register` (idempotent `FIND_OR_CREATE_VERSION`) | Fails only on a genuine Maven/plugin error — this is a post-merge publish step, not a gate |

Because `schema-compat-check.yml`, `schema-governance-bootstrap.yml`, and `schema-register.yml` all
require the `[self-hosted, apicurio-local]` runner label (they assume a standing registry reachable
at `http://localhost:8080` on the runner host), you can't exercise them via a plain `act`/fork PR
without that runner configured — but `schema-drift-check.yml` needs nothing beyond `ubuntu-latest`
and can be exercised locally exactly as in §3.

**What you verified:** you understand which of the four workflows is a hard merge gate
(`schema-compat-check`), which is informational-but-still-gating (`schema-drift-check`), and which
two are operational/one-shot (`schema-governance-bootstrap`, `schema-register`) rather than PR gates.

---

## 17. Health-indicator failure scenarios

There is no runtime registry health probe (ADR-0004). Remaining scenario:

**DLQ has messages:**

```bash
curl -s -X POST http://localhost:8081/api/orders/poison   # from §10, lands a message on orders.created.dlq
curl -s localhost:8082/actuator/health | jq '.components.queueDepth'
```

Expect `status: DOWN` — `QueueDepthHealthIndicator` is DOWN whenever *any* DLQ has more than zero
messages (main-queue depth alone is informational, not a health signal). Drain the DLQ (management
UI → purge, or manually ack the message) and re-check to see it return to `UP`.

**What you verified:** queue-depth health surfaces poison messages piling up through the standard
actuator surface.

---

## 18. Teardown

```bash
docker compose down       # stop containers, keep the pgdata volume (registered schemas persist)
docker compose down -v    # also remove the pgdata volume — next `compose up` starts from an empty registry
```

Stop both Spring Boot services with `Ctrl-C` in their terminals.

---

## 19. Quick reference

**UIs / endpoints:**

| UI / endpoint | URL | Credentials |
|---|---|---|
| Apicurio Registry UI | http://localhost:8888 | none |
| Apicurio Registry API | http://localhost:8080 | none |
| RabbitMQ management | http://localhost:15672 | `guest` / `guest` |
| Producer health | http://localhost:8081/actuator/health | none |
| Consumer health | http://localhost:8082/actuator/health | none |

**Seed data used throughout this guide** (copy-paste, no need to re-derive UUIDs):

```json
{"customerId":"11111111-1111-1111-1111-111111111111","productId":"22222222-2222-2222-2222-222222222222","quantity":2,"totalAmount":99.99,"currency":"USD"}
{"orderId":"33333333-3333-3333-3333-333333333333","trackingNumber":"1Z999AA10123456784","carrier":"UPS"}
{"orderId":"33333333-3333-3333-3333-333333333333","reason":"Customer request","refundAmount":49.99}
{"email":"alice@example.com","firstName":"Alice","lastName":"Smith","phoneNumber":"+15551234567"}
{"customerId":"44444444-4444-4444-4444-444444444444","address":{"line1":"221B Baker Street","city":"London","postalCode":"NW1 6XE","countryCode":"GB"}}
{"customerId":"44444444-4444-4444-4444-444444444444","previousTier":"BRONZE","newTier":"GOLD"}
```

**Command reference:**

```bash
./mvnw clean install -DskipTests                          # build everything
./mvnw test                                                # unit tests only
./mvnw verify                                              # unit + Testcontainers IT
./mvnw -pl order-contracts,customer-contracts -am process-classes  # regenerate schemas
./mvnw -pl order-contracts,customer-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl order-contracts verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl producer-service spring-boot:run                # port 8081
./mvnw -pl consumer-service spring-boot:run                # port 8082
docker compose up / down / down -v
```
