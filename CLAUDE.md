# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: minimal cut (branch `minimal-poc`)

This branch is the **minimal teaching cut** of the full POC: one message type (`OrderCreated`),
a single 5s retry tier, real Apicurio + RabbitMQ. The aggressive simplification (5 modules → 4,
core 32 → ~19 classes) is intentional — it keeps all three core concepts demonstrable with the
least code. The step-by-step runbook with seed data is **`minimal-poc-guide.md`** at the repo root.

The original spec-derived plan documents live in `docs/` as historical record (they describe the
full POC, not this cut):

- `docs/POC-Implementation-Plan.md` — phase plan (architecture, wire format, topology, failure
  model, acceptance criteria; tasks `T-`, tests `TC-`, gates `AC-`).
- `docs/TODO.md` — checkbox execution breakdown.

## What this is

A Maven multi-module POC proving end-to-end **schema-governed messaging**: Apicurio Registry
3.2.0 (schema source of truth) + RabbitMQ (transport) + Spring Boot 4.0 services on **Java 25**.
It demonstrates a single **JSON Schema** message type (`OrderCreated`) flowing producer →
registry → consumer, schema-compatibility governance enforced at **real registration time** via
the Apicurio Maven plugin, and a DLX/DLQ + single-tier retry failure topology.

## Commands (as the project is built per the plan)

The build uses the **committed Maven Wrapper** (`./mvnw`) — always prefer it over a system `mvn`.

```bash
./mvnw clean install                    # full build (all modules)
./mvnw test                             # FAST unit tests only (Surefire, *Test.java)
./mvnw verify                           # unit + Testcontainers integration tests (Failsafe, *IT.java)
./mvnw -pl schema-messaging-core test   # test a single module
./mvnw -pl schema-messaging-core compile

# Schema governance — real registration IS the gate (requires a running registry)
./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080

# Optional dry-run pre-flight (checks compatibility without writing a version)
./mvnw -pl order-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080

# Incompatible-change demo (triggers rejection from a running registry — must fail)
./mvnw -pl order-contracts verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080

# Run services (producer :8081, consumer :8082)
./mvnw -pl producer-service spring-boot:run
./mvnw -pl consumer-service spring-boot:run

# Infrastructure (Postgres, Apicurio + UI, RabbitMQ-management)
docker compose up                       # cold start must reach all-healthy
# Then register the schema from the host, rule-first (order-contracts carries the apicurio-registry plugin).
# Full sequence in minimal-poc-guide.md §4: baseline → attach rule → dry-run → register current.
#   1. register the v1 baseline (creates the artifact — a rule can only attach to an existing artifact):
./mvnw -pl order-contracts verify -Pbaseline -Dapicurio.registry.url=http://localhost:8080
#   2. attach the FORWARD compatibility rule via REST (register does not):
#      POST /apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules {"ruleType":"COMPATIBILITY","config":"FORWARD"}
#   3. (optional) dry-run pre-flight:  ./mvnw -pl order-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
#   4. register the current schema for real (now governed by the FORWARD rule):
./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
```

Run a single test class/method with the standard Surefire/Failsafe selectors, e.g.
`./mvnw -pl schema-messaging-core test -Dtest=SchemaAwareMessageConverterTest`.

**Test split is load-bearing:** Surefire (`*Test.java`) stays fast and mock-based; Failsafe
(`*IT.java`, Testcontainers) runs only on `verify`. Keep new tests on the correct side.

## Build conventions (enforced — do not violate)

- **Single source of versions:** all versions live in the parent POM
  `<properties>`/`<dependencyManagement>` (Spring Boot **4.0.x** BOM imported). Module POMs
  inherit and **never re-declare versions**. Stay on Spring Boot 4.0.x — **not 4.1**.
- **Java 25** via `maven-toolchains-plugin` + `maven-compiler-plugin <release>25</release>`
  (bytecode major version 69). Requires a JDK-25 entry in `~/.m2/toolchains.xml`; the Maven
  daemon JVM may differ from the compile toolchain.
- **`core ↛ contracts` rule is machine-enforced:** `schema-messaging-core` uses
  `maven-enforcer-plugin` `bannedDependencies` to forbid `*-contracts` modules. The core
  library is domain-agnostic and must never depend on a contracts module.
- **Maven is the POC build by design.** Schema registration/compat-gating use the official
  `apicurio-registry-maven-plugin` (no trusted Gradle equivalent). A Gradle migration is
  explicitly **deferred post-POC** — do not start it (see plan §0.4).

## Architecture (the big picture)

Four Maven modules (parent root = this directory):

- **`schema-messaging-core`** — domain-agnostic library (`jar`, no Spring Boot repackage).
  All the reusable plumbing lives here; the services and contracts plug into it.
- **`order-contracts`** — `OrderCreated` JSON Schema + generated POJOs
  (`com.example.contracts.orders.*`) + its AMQP topology. Depends only on Jackson + spring-amqp.
- **`producer-service`** / **`consumer-service`** — Spring Boot apps that depend on core +
  `order-contracts`.

### Schema-aware message flow

The center of the design is `SchemaAwareMessageConverter` (a Spring AMQP `MessageConverter`):

- **Produce** (`toMessage`): look up the Java type in the `TypeMappingRegistry` → resolve the
  schema via `SchemaResolver` → **validate** the payload → serialize via `JsonSchemaStrategy` →
  populate `X-Schema-*` headers + content-type. Validation failure throws
  `SchemaValidationException` and **no message is emitted**.
- **Consume** (`fromMessage`): read `X-Schema-*` headers → resolve schema (preferring
  `X-Schema-GlobalId` to skip the coordinate lookup) → validate + deserialize → return the
  typed object.

Supporting pieces in core:
- **`ApicurioClient`** — thin façade over the Apicurio Java SDK (`fetchByGlobalId`,
  `fetchByCoordinates`, `latestVersion`); anonymous access.
- **`SchemaResolver`** — Caffeine-backed cache over `ApicurioClient` with two deliberately
  different caches: `byCoordinates` (mutable — `latest` can advance) is a `LoadingCache` with
  `expireAfterWrite` (TTL) + `refreshAfterWrite` (async stale-while-revalidate); `byGlobalId`
  (immutable mapping) is size-bounded only, **no TTL**. Both are size-bounded and
  `recordStats()`-enabled. On a registry outage it serves **last-known-good** content and only
  throws `RegistryUnavailableException` when nothing is cached. Tunable via `apicurio.cache.*`
  (`ApicurioCacheProperties`).
- **`JsonSchemaStrategy`** — the sole serde (networknt validation + Jackson). Each contracts
  module contributes one `TypeMapping` bean (Java type ↔ coordinates ↔ type ↔ routing).
- **`EventPublisher`** / **`EventConsumerSupport`** wrap `RabbitTemplate` / `@RabbitListener`
  and own the failure-routing decision.

### Wire format (strict — spec §6)

Message body is the **raw serialized bytes only**: the JSON document, no envelope.
Schema identity travels entirely in `X-Schema-*` headers, plus
`X-Message-Id` / `X-Correlation-Id`. Content-type is `application/json`.

### Failure model (spec §9/§11)

Consumer declares the topology idempotently on startup: `events.exchange`, `events.dlx`,
`events.retry.exchange` + queues/bindings. **Transient** failures (registry unavailable,
schema-not-found, downstream errors) go through the retry exchange via a **single 5s tier**
(`orders.created.retry.5s`), cycled up to `events.retry.max-attempts` (default 3) before the
DLQ. **Permanent** failures (validation, deserialization, type mismatch) go **straight to the
DLQ, no retry**. DLQ messages carry the full `X-Failure-*` header set (stack trace truncated to
4KB). (Idempotency/dedupe was cut in this minimal version.)

### Exception taxonomy (drives routing)

`SchemaNotFoundException`, `SchemaValidationException`, `DeserializationException`,
`SerializationException`, `IncompatibleSchemaTypeException`, `RegistryUnavailableException`.
The mapping from exception → retry-vs-DLQ decision is the contract `EventConsumerSupport` tests
table-drive — keep them aligned.

### Schema governance

`OrderCreated` is registered under group `events.orders` with a **FORWARD** rule — Apicurio's
JSON Schema checker classifies adding any property as `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`,
which BACKWARD rejects but FORWARD accepts (so optional JSON field additions only validate under
FORWARD). Governance is enforced by the **real (non-dry-run) `apicurio-registry:register` Maven
command, run manually** — the registry rejects an incompatible schema and the goal fails. The
`compat-check` profile (`register -DdryRun`) is kept only as an optional pre-flight; the
`incompatible-demo` profile reproduces a rejection on demand. (Schema-version pinning and the
`auto-register=OFF` fail-fast validator were cut in this minimal version.)

### Health checks

Both services expose the default Spring Boot Actuator health endpoint at `/actuator/health`. (The
custom `QueueDepthHealthIndicator` and `RegistryHealthIndicator`, and the metrics/tracing stack,
were cut in this minimal version — watch the RabbitMQ management UI to see DLQ depth.)

### DLQ demo

`POST /api/orders/poison` on the producer service publishes garbage JSON bytes directly via
`RabbitTemplate` (bypasses the converter), triggering a `SchemaValidationException` on the
consumer (unparseable JSON fails validation before deserialization) and landing the message
on the DLQ with all `X-Failure-*` headers populated.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec
