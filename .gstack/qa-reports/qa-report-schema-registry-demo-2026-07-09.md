# QA Report — schema-registry-demo

**Date:** 2026-07-09
**Branch:** `all-pojo-to-schema` (diff-aware mode, compared against `main`)
**Mode:** Full (backend/API — no web frontend to browse; this is a Maven multi-module
messaging POC: Apicurio Registry + RabbitMQ + two Spring Boot services)
**Duration:** ~25 minutes
**Framework:** Spring Boot 4.0.x / Java 25, REST + AMQP, no SPA/server-rendered UI

> **Note on methodology:** this project has no traditional web page to click through. Its
> "surface area" is a REST API (producer-service :8081), an AMQP consumer (consumer-service
> :8082), a schema registry (Apicurio, with a web UI), and RabbitMQ (with a management UI).
> The Chrome extension used for browser automation was not connected in this session, so the
> two web UIs (Apicurio UI, RabbitMQ management UI) were verified via their REST APIs instead
> of visual screenshots — both returned expected state. Everything else (REST endpoints, AMQP
> topology, DLQ routing, schema governance gates) was exercised end-to-end against live,
> locally-running infrastructure (Docker Compose: Postgres, Apicurio, RabbitMQ).

## Summary

| Category | Score | Notes |
|---|---|---|
| Functional (API/messaging) | 100 | All golden-path and edge-case flows behaved correctly |
| Console/Logs | 100 | No unexpected errors in producer/consumer logs |
| Schema Governance | 100 | Drift check, compat-check, and incompatible-demo all behaved per spec |
| Visual/UX/Accessibility | N/A | No web frontend in this app |
| Performance | Not measured | Out of scope for this pass |

**Overall health score: 100/100** (within the categories applicable to this project — see note above)

No bugs found. This is a clean pass.

## What was tested

### 1. Build & unit tests
- `./mvnw clean install -DskipITs` → **BUILD SUCCESS**, all Surefire unit tests pass across
  all 9 modules (including the two new `*SchemaDeterminismTest` classes added on this branch
  for `order-contracts` and `customer-contracts`).

### 2. Infrastructure
- `docker compose up -d` → Postgres, Apicurio, Apicurio UI, RabbitMQ all reached `healthy`.

### 3. Schema registration & governance (spec §D4/D5/D6, CLAUDE.md "Schema governance")
- Registered all 6 artifacts via `apicurio-registry-maven-plugin` — confirmed via registry
  REST API (`/apis/registry/v3/search/artifacts`, count=6, correct `events.orders` /
  `events.customers` groups).
- Confirmed all 6 artifacts carry the `FORWARD` compatibility rule (persisted from a prior
  session's Postgres volume — re-verified, not stale).
- **Offline drift check** (`process-classes` + `git diff --exit-code` on generated schemas):
  clean, no drift — confirms `schema-gen-tools`' `SchemaGeneratorCli`/`SchemaGenerator`
  (touched on this branch) still produces byte-identical output to what's committed.
- **`compat-check` profile** against the live registry: passes clean (no incompatible changes
  on this branch, as expected).
- **`incompatible-demo` profile** (`order-contracts`, `-Pincompatible-demo`): correctly
  **fails the build** (exit code 1) with `RuleViolationProblemDetails` from the registry — the
  CI merge gate's core acceptance criterion holds. Registry artifact count stayed at 6
  afterward (rejected registration didn't leak into registry state).

### 4. Services boot & health
- `producer-service` (:8081) and `consumer-service` (:8082) both started cleanly and reported
  `status: UP` on `/actuator/health`, including the `registry` health indicator
  (`preWarmCompleted: true, preWarmErrors: 0`) and, on the consumer, `queueDepth` showing all
  12 domain-scoped queues (6 main + 6 DLQ).

### 5. AMQP topology (this branch's main change — contract-owned topology, ADR-0003)
Verified via RabbitMQ management API that both `order-contracts` and `customer-contracts`
auto-configurations correctly declared **fully domain-scoped** topology:
- `events.orders.exchange` / `events.orders.dlx` / `events.orders.retry.exchange`
- `events.customers.exchange` / `events.customers.dlx` / `events.customers.retry.exchange`
- Per routing key (6 total): main queue, DLQ, and 3 retry-ladder queues (5s/30s/5m) — 30
  queues total, all present and correctly named per `TopologyNaming` conventions.

### 6. REST API — golden path
All via producer-service (:8081), confirmed 201/202 responses and correct downstream
consumption (queue depths returned to 0 after processing):
- `POST /api/orders` (OrderCreated) → 201
- `POST /api/orders/ship` (OrderShipped) → 201
- `POST /api/orders/cancel` (OrderCancelled) → 201
- `POST /api/customers` (CustomerRegistered) → 201
- `POST /api/customers/address` (CustomerAddressAdded, nested `Address` value object) → 201
- `POST /api/customers/tier` (CustomerTierChanged) → 201

### 7. REST API — edge cases / validation
- `POST /api/orders` with missing required fields → **400**, schema-validation error names
  every missing field (`currency`, `productId`, `quantity`, `totalAmount`).
- `POST /api/customers` with an invalid email → **400**, schema-validation error correctly
  flags `$.email` against the RFC 5321 pattern.
- `POST /api/customers/address` with a flat (non-nested) address shape → **400**, correctly
  rejected as missing the nested `address` object (this was my own test mistake, not a bug —
  confirmed by reading `CustomerController`/`Address.java`; the corrected nested payload with
  `line1`/`city`/`postalCode`/`countryCode` succeeded).

### 8. DLQ / failure-routing demo (spec §9/§11)
- `POST /api/orders/poison` → 202, publishes malformed JSON bytes bypassing the converter.
- Confirmed the message landed **directly on `orders.created.dlq`** (no retry — correct, since
  unparseable JSON is a permanent/deserialization failure, not transient) with
  `X-Failure-Reason: DLQ_DIRECT`.
- Confirmed `X-Failure-Message` carries the **actual root cause**
  (`JsonParseException: Unexpected character ('N'...)`), not a generic wrapper message — this
  matches a previously-fixed bug (see prior session's `feedback_dlq_failure_message` learning)
  and it's still holding on this branch.
- All other 11 queues stayed empty — no stray retries or misrouting from the poison test.

## Issues found

None. Zero bugs, zero regressions.

## Things noted but out of scope for this pass

- Visual verification of the Apicurio UI and RabbitMQ management UI was not possible (Chrome
  extension not connected this session) — recommend a follow-up pass with the browser
  connected if visual/UX review of those third-party UIs is wanted.
- Performance/load characteristics were not measured.
- `docs/TODO.md` contains a couple of unchecked boxes (T-3.4, AC-3.1) but they reference an
  older payment-contracts phase numbering scheme, not anything on this branch — not followed
  up as part of this QA pass.

## Environment left running

Docker Compose infra (Postgres, Apicurio, Apicurio UI, RabbitMQ) was left running and healthy
in case it's needed for further work. `producer-service` and `consumer-service` were stopped
after testing.
