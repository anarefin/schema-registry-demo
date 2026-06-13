# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: fully implemented (Phases 0–6 complete)

All six phases are done. One minor task is outstanding:

- **T-3.4** — Register `OrderCreated` to a live Apicurio registry:
  `./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080`

Planning documents live in `docs/`:

- `docs/POC-Implementation-Plan.md` — spec-derived phase plan (architecture, wire format,
  topology, failure model, acceptance criteria; tasks `T-`, tests `TC-`, gates `AC-`).
- `docs/TODO.md` — checkbox execution breakdown with done-checks; treat as the source of truth
  if scope changes.

## What this is

A Maven multi-module POC proving end-to-end **schema-governed messaging**: Apicurio Registry
3.2.0 (schema source of truth) + RabbitMQ (transport) + Spring Boot 4.0 services on **Java 25**.
It demonstrates **JSON Schema** message types (orders + customers) flowing producer → registry
→ consumer, schema-compatibility governance as a CI merge gate, and a full DLX/DLQ/retry
failure topology.

## Commands (as the project is built per the plan)

The build uses the **committed Maven Wrapper** (`./mvnw`) — always prefer it over a system `mvn`.

```bash
./mvnw clean install                    # full build (all modules)
./mvnw test                             # FAST unit tests only (Surefire, *Test.java)
./mvnw verify                           # unit + Testcontainers integration tests (Failsafe, *IT.java)
./mvnw -pl schema-messaging-core test   # test a single module
./mvnw -pl schema-messaging-core compile

# Schema governance (official apicurio-registry-maven-plugin; requires a running registry)
./mvnw -pl customer-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl order-contracts     apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080

# CI merge gate — fails on incompatible schema changes
./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check

# Incompatible-change demo (triggers rejection from a running registry)
./mvnw -pl order-contracts    verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl customer-contracts verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080

# Run services (producer :8081, consumer :8082)
./mvnw -pl producer-service spring-boot:run
./mvnw -pl consumer-service spring-boot:run

# Infrastructure (Postgres, Apicurio + UI, RabbitMQ-management)
docker compose up                       # cold start must reach all-healthy
# Then register schemas from the host (the contracts modules carry the apicurio-registry plugin):
./mvnw -pl order-contracts,customer-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
# ...and attach the compatibility rules via REST (register does not — see README §2):
#   OrderCreated / CustomerRegistered (both JSON Schema) → FORWARD
#   (adding a JSON property is only FORWARD-compatible in Apicurio:
#    BACKWARD rejects it as NARROWED)
#   POST /apis/registry/v3/groups/{group}/artifacts/{id}/rules {"ruleType":"COMPATIBILITY","config":"<LEVEL>"}
```

Run a single test class/method with the standard Surefire/Failsafe selectors, e.g.
`./mvnw -pl schema-messaging-core test -Dtest=SchemaResolverTest#cacheHit`.

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

Five Maven modules (parent root = this directory):

- **`schema-messaging-core`** — domain-agnostic library (`jar`, no Spring Boot repackage).
  All the reusable plumbing lives here; the two services and contracts plug into it.
- **`order-contracts`** — `OrderCreated` JSON Schema + generated POJOs
  (`com.example.contracts.orders.*`). Depends only on Jackson.
- **`customer-contracts`** — `CustomerRegistered` JSON Schema + generated POJOs
  (`com.example.contracts.customers.*`). Depends only on Jackson.
- **`producer-service`** / **`consumer-service`** — Spring Boot apps that depend on core +
  both contracts modules.

### Schema-aware message flow

The center of the design is `SchemaAwareMessageConverter` (a Spring AMQP `MessageConverter`):

- **Produce** (`toMessage`): look up the Java type in the `TypeMappingRegistry` → resolve the
  schema via `SchemaResolver` → **validate** the payload → serialize via the type's
  `SerializationStrategy` → populate `X-Schema-*` headers + content-type. Validation failure
  throws `SchemaValidationException` and **no message is emitted**.
- **Consume** (`fromMessage`): read `X-Schema-*` headers → resolve schema (preferring
  `X-Schema-GlobalId` to skip the coordinate lookup) → dispatch to the matching strategy →
  return the typed object.

Supporting pieces in core:
- **`ApicurioClient`** — thin façade over the Apicurio Java SDK (`fetchByGlobalId`,
  `fetchByCoordinates`, `latestVersion`); accepts a bearer-token supplier (OIDC, off by default).
- **`SchemaResolver`** — Caffeine cache (`byGlobalId` + `byCoordinates`) with TTL +
  refresh-after-write; pre-warms on startup; on registry outage **serves stale from cache**
  and only throws `RegistryUnavailableException` when nothing is cached.
- **`SerializationStrategy`** SPI with `JsonSchemaStrategy` as the sole built-in strategy
  (the SPI remains for future formats, e.g. Avro). Each contracts module contributes one
  `TypeMapping` bean (Java type ↔ coordinates ↔ type ↔ routing).
- **`EventPublisher`** / **`EventConsumerSupport`** wrap `RabbitTemplate` / `@RabbitListener`
  and own the failure-routing decision.

### Wire format (strict — spec §6)

Message body is the **raw serialized bytes only**: the JSON document, no envelope.
Schema identity travels entirely in `X-Schema-*` headers, plus
`X-Message-Id` / `X-Correlation-Id`. Content-type is `application/json`.

### Failure model (spec §9/§11)

Consumer declares the topology idempotently on startup: `events.exchange`, `events.dlx`,
`events.retry.exchange` + queues/bindings. **Transient** failures (registry unavailable,
schema-not-found, downstream errors) go through the retry exchange with a TTL ladder
(5s/30s/5m, max 3) before the DLQ. **Permanent** failures (validation, deserialization, type
mismatch) go **straight to the DLQ, no retry**. DLQ messages carry the full `X-Failure-*`
header set (stack trace truncated to 4KB). Consumer dedupes on `X-Message-Id` (POC-only,
in-memory Caffeine).

### Exception taxonomy (drives routing)

`SchemaNotFoundException`, `SchemaValidationException`, `DeserializationException`,
`SerializationException`, `IncompatibleSchemaTypeException`, `RegistryUnavailableException`.
The mapping from exception → retry-vs-DLQ decision is the contract `EventConsumerSupport` tests
table-drive — keep them aligned.

### Schema governance

Both artifacts are registered under groups `events.orders` / `events.customers` and carry a
**FORWARD** rule — Apicurio's JSON Schema checker classifies adding any property as
`OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`, which BACKWARD rejects but FORWARD accepts (so optional
JSON field additions only validate under FORWARD). The `compat-check` Maven profile in both
contracts POMs wires `apicurio-registry:register -DdryRun` as the CI merge gate: an incompatible
change fails the goal and fails the merge. Producers can pin a schema version via `schema.{orders,customers}.pinned-version` in
`application.yml`; with `auto-register=OFF` an unregistered pinned schema must **fail fast on
startup**.

### Health checks

`QueueDepthHealthIndicator` (consumer) and `RegistryHealthIndicator` (core) expose Spring Boot
Actuator health checks at `/actuator/health` on both services. (The metrics/tracing stack —
Micrometer, Prometheus, Grafana, OpenTelemetry/Jaeger — was removed from the POC.)

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
