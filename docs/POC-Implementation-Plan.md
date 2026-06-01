# Apicurio + RabbitMQ Schema Registry POC — Implementation Plan (Maven)

A phase-by-phase, task-level execution plan derived from the project specification.
The POC is built with **Maven** (matching the spec), because Apicurio ships an official,
maintained **Maven** plugin for schema registration and compatibility-gating and has no
equivalent Gradle plugin. Conversion to Gradle is **deferred until after the POC is
complete** and is captured as scoped future work in §0.4 and §10.

This plan expands every spec phase into a checkable task list, an explicit set of test
cases, and phase-exit acceptance criteria.

> Scope note: the architecture, wire format, topology, failure model, and observability
> requirements are unchanged from the spec. This document adds task / test / acceptance
> detail on top of the spec's Maven build.

---

## 0. Build System: Maven (POC) — Gradle Deferred

### 0.1 Why Maven for the POC — schema registration is the deciding factor

Schema **registration** and **compatibility-checking** are core to this POC (spec §7, §12).
Apicurio provides these as an **official, maintained Maven plugin** (`apicurio-registry-maven-plugin`,
goals `register`, `download`, `test`). There is **no official Gradle plugin**; the only
community option (`net.croz.apicurio-registry-gradle-plugin`) was last released v1.1.0 in
January 2023, predating Apicurio Registry 3.x and its v3 REST API, and is not trusted for
this work.

**Decision:** build the POC on Maven and use the official plugin directly. This keeps the
build path identical to Apicurio's documented Kafka-registry workflow and removes the need
for any custom registration tooling.

### 0.2 Build wiring (affirms spec §3 / §7 / §14, made concrete)

| Concern                         | Maven mechanism                                                                 |
|---------------------------------|---------------------------------------------------------------------------------|
| Multi-module project            | Parent `pom.xml` with `<modules>`; one `pom.xml` per module                     |
| Dependency management           | Parent `<dependencyManagement>` importing the **Spring Boot 4.0 BOM** + pinned versions |
| Reproducible CLI                | **Maven Wrapper** (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) — committed              |
| Java 25                         | `maven-toolchains-plugin` (`~/.m2/toolchains.xml`) + `maven-compiler-plugin` `<release>25</release>` |
| Spring Boot apps                | `spring-boot-maven-plugin` (`repackage` → executable jar; `spring-boot:run`)    |
| Protobuf codegen                | `protobuf-maven-plugin` + pinned `protoc`                                       |
| JSON Schema → POJO              | `jsonschema2pojo-maven-plugin`                                                   |
| Schema register / compat-test   | `apicurio-registry-maven-plugin` goals `register` and `test`                     |
| Unit tests                      | `maven-surefire-plugin` (`*Test.java`) → `mvn test`                              |
| Integration tests (Testcontainers) | `maven-failsafe-plugin` (`*IT.java`) → `mvn verify`                          |
| Module dependency rule          | `maven-enforcer-plugin` `bannedDependencies` (see §0.3)                          |
| Full build                      | `mvn clean install`                                                             |

### 0.3 Conventions used throughout

- **Versions** live in parent-POM properties / `<dependencyManagement>`; no version strings
  scattered in module POMs. (Spring Boot 4.0.x latest patch — **not 4.1** per spec §19.)
- **Core↛contracts rule enforced:** `schema-messaging-core/pom.xml` configures
  `maven-enforcer-plugin` `bannedDependencies` to forbid `*-contracts` modules, failing the
  build if the edge ever appears (spec §5 "Rule").
- **Unit vs integration split:** Surefire runs unit tests on `mvn test`; Failsafe runs
  Testcontainers integration tests (`*IT`) on `mvn verify`, keeping `test` fast.

### 0.4 Deferred Gradle migration (post-POC, out of scope now)

Captured so it isn't lost. After acceptance, migration to Gradle would entail:

- Parent POM → root `settings.gradle.kts` + version catalog `libs.versions.toml` + `build-logic` convention plugins.
- `apicurio-registry-maven-plugin` → **custom Gradle tasks on the Apicurio Java SDK 3.x**
  (`registerSchemas` / `checkSchemaCompatibility`), since no trusted Gradle plugin exists.
- protobuf / jsonschema2pojo Maven plugins → their `com.google.protobuf` / `org.jsonschema2pojo` Gradle equivalents.
- Failsafe IT → JVM Test Suite `integrationTest` suite.
- Toolchains → Gradle Java toolchain + Foojay resolver.

This is tracked as a single future-work epic; do **not** begin it until all §18 acceptance
criteria pass on the Maven build.

### 0.5 Module layout (Maven)

```
poc-parent/                         (parent pom.xml)
├── pom.xml                         # parent: modules, dependencyManagement, plugin mgmt
├── mvnw / mvnw.cmd / .mvn/         # committed wrapper
├── schema-messaging-core/pom.xml
├── order-contracts/pom.xml
├── customer-contracts/pom.xml
├── producer-service/pom.xml
├── consumer-service/pom.xml
└── docker-compose.yml
```

---

## Legend

- Task IDs: `T-<phase>.<n>` — checkboxes to track execution.
- Test IDs: `TC-<phase>.<n>` — each names what it asserts and its type (U=unit, I=integration, M=manual).
- Acceptance IDs: `AC-<phase>.<n>` — phase exit gate.
- Spec acceptance criteria (spec §18) referenced as `[SPEC-AC-n]` and mapped in §9.

---

## Phase 0 — Infrastructure & Toolchain

**Objective:** a buildable, runnable skeleton; Java 25 + Maven proven; all infra healthy.

### Tasks

- [ ] **T-0.1** Initialize the multi-module Maven project: parent `pom.xml` listing the five
  modules under `<modules>`.
- [ ] **T-0.2** Commit the Maven Wrapper; verify `./mvnw -version`.
- [ ] **T-0.3** Populate parent `<dependencyManagement>`: import the Spring Boot 4.0 BOM and
  pin all spec §3 versions (Apicurio 3.2.0, Postgres 17, RabbitMQ 3.13+, protobuf-java 3.25+,
  networknt validator, jsonschema2pojo, Micrometer/OTel). **Spring Boot 4.0.x — not 4.1.**
- [ ] **T-0.4** Configure `maven-toolchains-plugin` + `maven-compiler-plugin` `<release>25</release>`;
  add a documented `~/.m2/toolchains.xml` for JDK 25.
- [ ] **T-0.5** Confirm compilation targets Java 25 (`mvn -pl schema-messaging-core compile`
  produces class file major version 69).
- [ ] **T-0.6** Create empty module POMs so `mvn -q help:evaluate`/`mvn validate` resolves all five.
- [ ] **T-0.7** Author `docker-compose.yml`: `postgres:17`, `apicurio/apicurio-registry:3.2.0`
  (SQL storage per spec §8), `apicurio/apicurio-registry-ui:3.2.0`, `rabbitmq:3.13-management`.
- [ ] **T-0.8** Add healthchecks to every compose service; wire `depends_on: condition: service_healthy`.
- [ ] **T-0.9** Spike: add a throwaway `.proto` and `.json` schema; run `protobuf-maven-plugin`
  and `jsonschema2pojo-maven-plugin` under Java 25; confirm generated sources compile.
  **(Spec §3 toolchain risk — must clear before Phase 1.)**
- [ ] **T-0.10** Configure `maven-enforcer-plugin` `bannedDependencies` in
  `schema-messaging-core` to forbid the `*-contracts` modules (§0.3).
- [ ] **T-0.11** Add `maven-failsafe-plugin` to the build so an `*IT` integration-test phase
  exists from the start (used heavily from Phase 2 on).

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-0.1 | M | `./mvnw -version` reports a JVM/toolchain capable of Java 25. |
| TC-0.2 | U | `compile` on a sample class emits bytecode major version 69 (Java 25). |
| TC-0.3 | I | `docker compose up` brings **all** services to `healthy`; Apicurio UI and RabbitMQ management UI return HTTP 200. |
| TC-0.4 | I | `protobuf-maven-plugin` generates and compiles a sample message class under Java 25. |
| TC-0.5 | I | `jsonschema2pojo-maven-plugin` generates and compiles a sample POJO under Java 25. |
| TC-0.6 | U | Enforcer **fails** the build when a deliberate `core → order-contracts` dependency is added (negative test), and **passes** when removed. |
| TC-0.7 | M | `mvn validate` resolves all five modules + parent. |

### Acceptance Criteria (exit gate)

- [ ] **AC-0.1** `./mvnw clean install` succeeds on the skeleton (no real logic yet). `[SPEC-AC-1 groundwork]`
- [ ] **AC-0.2** `docker compose up` reaches all-healthy from cold start. `[SPEC-AC-1]`
- [ ] **AC-0.3** Both codegen plugins confirmed working under Java 25 (TC-0.4, TC-0.5). `[SPEC §3 risk closed]`
- [ ] **AC-0.4** Core↛contracts dependency rule is machine-enforced (TC-0.6).

---

## Phase 1 — Shared Core Library (`schema-messaging-core`)

**Objective:** domain-agnostic plumbing complete per spec §10.1, fully unit-tested with mocks.

### Tasks

- [ ] **T-1.1** `ApicurioClient` façade over the Apicurio Java SDK: `fetchByGlobalId(long)`,
  `fetchByCoordinates(groupId, artifactId, version)`, `latestVersion(groupId, artifactId)`;
  accepts a configurable bearer-token supplier (spec §16).
- [ ] **T-1.2** Value objects: `SchemaCoordinates`, `ResolvedSchema`, enum `SchemaType {PROTOBUF, JSON}`.
- [ ] **T-1.3** `SchemaResolver` caching layer over Caffeine: `byGlobalId` + `byCoordinates`
  caches; config keys `apicurio.cache.{max-size,ttl,refresh-after-write}` (spec §10.2).
- [ ] **T-1.4** Cache pre-warming on startup (iterate `TypeMapping`s, pre-fetch; WARN on
  failure, start anyway).
- [ ] **T-1.5** Registry-unavailable fallback: serve-from-cache + mark `stale-served`; only
  fail when not cached at all.
- [ ] **T-1.6** `SerializationStrategy` SPI + `ProtobufStrategy` and `JsonSchemaStrategy`.
- [ ] **T-1.7** `TypeMapping` record + `TypeMappingRegistry` collecting service-contributed beans.
- [ ] **T-1.8** `SchemaAwareMessageConverter` (Spring AMQP `MessageConverter`): `toMessage`
  (lookup → resolve → validate → serialize → populate headers) and `fromMessage`
  (read headers → resolve → dispatch strategy → return typed object).
- [ ] **T-1.9** `MessageHeaders` constants + header codec for all `X-Schema-*` headers (spec §6).
- [ ] **T-1.10** `EventPublisher` (wraps `RabbitTemplate` + converter) and `EventConsumerSupport`
  (`@RabbitListener` adapter + failure routing).
- [ ] **T-1.11** Exception taxonomy: `SchemaNotFoundException`, `SchemaValidationException`,
  `DeserializationException`, `SerializationException`, `IncompatibleSchemaTypeException`,
  `RegistryUnavailableException` (spec App. B).
- [ ] **T-1.12** Observability hooks (Micrometer meters registered per spec §15; no-op safe).
- [ ] **T-1.13** Maven: this module is `<packaging>jar</packaging>` (a library — **no**
  `spring-boot-maven-plugin` repackage); depends on Spring AMQP + Apicurio SDK + Caffeine.

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-1.1 | U | Cache **hit**: second `fetchByCoordinates` for same key does not call `ApicurioClient` (verify mock invoked once). |
| TC-1.2 | U | Cache **miss**: distinct coordinates trigger a fetch. |
| TC-1.3 | U | Cache **expiry**: after TTL, entry is re-fetched. |
| TC-1.4 | U | `refresh-after-write` triggers background refresh without blocking the caller. |
| TC-1.5 | U | **Pre-warm success** populates cache for every registered `TypeMapping`. |
| TC-1.6 | U | **Pre-warm failure** logs WARN and the context still starts. |
| TC-1.7 | U | **Registry-down + cached** → returns cached schema, marks `stale-served`, logs WARN. |
| TC-1.8 | U | **Registry-down + not cached** → throws `RegistryUnavailableException`. |
| TC-1.9 | U | `SchemaAwareMessageConverter` round-trip with a **mock** strategy: object → message → object is equal. |
| TC-1.10 | U | `toMessage` populates **all** required headers (spec §6) with correct values. |
| TC-1.11 | U | `fromMessage` with mismatched `X-Schema-Type` vs registered type throws `IncompatibleSchemaTypeException`. |
| TC-1.12 | U | Validation failure in `toMessage` throws `SchemaValidationException` and **does not** emit a message. |
| TC-1.13 | U | Unparseable bytes in `fromMessage` throw `DeserializationException`. |
| TC-1.14 | U | `EventConsumerSupport` maps each exception type to the correct routing decision (table-driven, one row per taxonomy entry). |

### Acceptance Criteria (exit gate)

- [ ] **AC-1.1** All §10.1 components exist with the prescribed names.
- [ ] **AC-1.2** `mvn -pl schema-messaging-core test` green; cache behaviors TC-1.1–1.8 all covered.
- [ ] **AC-1.3** Converter round-trip + header population proven with mocks (TC-1.9, TC-1.10).
- [ ] **AC-1.4** Module is a library jar with **zero** dependency on `*-contracts` (re-run TC-0.6).

---

## Phase 2 — JSON Schema End-to-End (`customer-contracts`)

**Objective:** `CustomerRegistered` (JSON Schema) flows producer → registry → consumer.

### Tasks

- [ ] **T-2.1** Add `src/main/resources/schemas/customer-registered.json` (Draft 2020-12).
- [ ] **T-2.2** Configure `jsonschema2pojo-maven-plugin` → POJOs under
  `com.example.contracts.customers.*` with Jackson annotations.
- [ ] **T-2.3** Expose one `TypeMapping` bean via a small `@Configuration` (chosen pattern;
  apply consistently — spec §10.3/§10.4 "pick one").
- [ ] **T-2.4** Register `CustomerRegistered` to `events.customers` via
  `apicurio-registry-maven-plugin:register` (JSON type, BACKWARD rule).
- [ ] **T-2.5** Producer maps a demo JSON body → `CustomerRegistered`, publishes via `EventPublisher`.
- [ ] **T-2.6** Consumer `@RabbitListener` on `customers.registered.queue` typed as `CustomerRegistered`.
- [ ] **T-2.7** Maven: `customer-contracts` is a `jar`, depends only on Jackson.

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-2.1 | U | jsonschema2pojo output deserializes a valid sample JSON into the POJO. |
| TC-2.2 | I | `apicurio-registry-maven-plugin:register` creates the `CustomerRegistered` artifact in Apicurio (query REST API confirms). |
| TC-2.3 | I | **Testcontainers round-trip (`*IT`):** producer publishes → consumer receives, validates, deserializes the same logical object. |
| TC-2.4 | I | Published message carries correct `content-type: application/json` + all `X-Schema-*` headers. |
| TC-2.5 | I | Valid-but-evolving payload (extra optional field) still deserializes (Jackson tolerance). |

### Acceptance Criteria (exit gate)

- [ ] **AC-2.1** `CustomerRegistered` visible in Apicurio UI under `events.customers`. `[SPEC-AC-3 partial]`
- [ ] **AC-2.2** End-to-end JSON round-trip passes via Testcontainers (TC-2.3). `[SPEC-AC-2 partial]`
- [ ] **AC-2.3** `apicurio-registry-maven-plugin:register` proven against a live 3.2.0 registry.

---

## Phase 3 — Protobuf End-to-End (`order-contracts`)

**Objective:** `OrderCreated` (Protobuf) flows producer → registry → consumer.

### Tasks

- [ ] **T-3.1** Add `src/main/resources/schemas/order-created.proto` (single-file, no imports — spec §19).
- [ ] **T-3.2** Configure `protobuf-maven-plugin` → classes under `com.example.contracts.orders.*`;
  pin `protoc` via parent properties.
- [ ] **T-3.3** `TypeMapping` bean (same pattern chosen in Phase 2).
- [ ] **T-3.4** Register `OrderCreated` to `events.orders` via
  `apicurio-registry-maven-plugin:register` (PROTOBUF type, BACKWARD rule).
- [ ] **T-3.5** Producer maps demo body → `OrderCreated` builder, publishes.
- [ ] **T-3.6** Consumer `@RabbitListener` on `orders.created.queue` typed as `OrderCreated`.
- [ ] **T-3.7** Maven: `order-contracts` is a `jar`, depends only on `protobuf-java`.

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-3.1 | U | Generated `OrderCreated` round-trips to/from protobuf binary bytes (no magic byte, no length prefix — spec §6). |
| TC-3.2 | I | `register` goal creates the `OrderCreated` PROTOBUF artifact (REST confirms). |
| TC-3.3 | I | **Testcontainers round-trip (`*IT`):** producer → consumer for `OrderCreated`. |
| TC-3.4 | I | Message carries `content-type: application/x-protobuf` + correct headers. |
| TC-3.5 | I | `X-Schema-GlobalId` present and lets the consumer resolve via `fetchByGlobalId` (skip coordinate lookup). |

### Acceptance Criteria (exit gate)

- [ ] **AC-3.1** `OrderCreated` visible in Apicurio UI under `events.orders`. `[SPEC-AC-3 partial]`
- [ ] **AC-3.2** End-to-end Protobuf round-trip passes (TC-3.3). `[SPEC-AC-2 complete]`
- [ ] **AC-3.3** Body is pure protobuf bytes — confirmed by inspecting a captured message (TC-3.1).

---

## Phase 4 — Schema Evolution & Compatibility

**Objective:** prove BACKWARD governance; demonstrate accept + reject paths in CI.

### Tasks

- [ ] **T-4.1** Confirm BACKWARD rule attached to both artifacts (set during registration).
- [ ] **T-4.2** Scenario 1 (compatible): add optional `promo_code` to `OrderCreated`, bump v2,
  register; producer stays pinned v1; v1 consumers still read. Switch producer to v2; v1
  consumers still read (BACKWARD).
- [ ] **T-4.3** Scenario 2 (incompatible): remove a required field / reuse a field number /
  change a type in `OrderCreated`; attempt registration → must be rejected.
- [ ] **T-4.4** Scenario 3 (JSON parallel): add optional property to `CustomerRegistered`,
  register v2 (accepted); attempt an incompatible change (rejected).
- [ ] **T-4.5** Wire `apicurio-registry-maven-plugin:test` (compatibility dry-run goal) as the
  **CI merge gate**: an incompatible change makes the goal fail and **fails the merge**.
- [ ] **T-4.6** Create a demo branch carrying an incompatible change; capture the failing-CI
  output + Apicurio rejection message as screenshots for the README.
- [ ] **T-4.7** Document version-pinning behavior: producer `application.yml` pins versions (spec §10.5).

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-4.1 | I | Registering compatible `OrderCreated` v2 via `register` **succeeds**; artifact now has ≥2 versions. |
| TC-4.2 | I | v1-pinned consumer still deserializes a v2-produced `OrderCreated` (BACKWARD holds). |
| TC-4.3 | I | `apicurio-registry-maven-plugin:test` on an incompatible `OrderCreated` change **fails** with a clear message. |
| TC-4.4 | I | Compatible `CustomerRegistered` v2 registers; incompatible change rejected. |
| TC-4.5 | M | CI run on the incompatible demo branch **fails the merge**; rejection message captured. |
| TC-4.6 | I | Producer pinned to a version reads that exact version's coordinates at runtime (header reflects pin). |

### Acceptance Criteria (exit gate)

- [ ] **AC-4.1** Both artifacts show ≥2 versions with BACKWARD rules in the UI. `[SPEC-AC-3]`
- [ ] **AC-4.2** Incompatible v3 registration via the Maven plugin **fails with a clear error** (TC-4.3). `[SPEC-AC-4]`
- [ ] **AC-4.3** Failing-CI evidence captured for the README (TC-4.5). `[SPEC §17 Phase 4]`

---

## Phase 5 — Validation & Dead-Letter Handling

**Objective:** full DLX/DLQ/retry topology; all five failure modes routed correctly.

### Tasks

- [ ] **T-5.1** Declare topology in consumer on startup (idempotent): exchanges
  `events.exchange`, `events.dlx`, `events.retry.exchange`; all queues + bindings per spec §9.
- [ ] **T-5.2** Implement retry path: transient failure → `events.retry.exchange` with TTL
  (5s/30s/5m exponential), `X-Retry-Count`, max 3, then DLQ.
- [ ] **T-5.3** Permanent failures (validation failed, deserialization error, type mismatch)
  → straight to DLQ, no retry.
- [ ] **T-5.4** `EventConsumerSupport` populates all DLQ headers per spec §11
  (`X-Failure-Reason/Message/StackTrace(4KB)/Original-Routing-Key/Failed-At/Retry-Count`).
- [ ] **T-5.5** Producer-side validate-then-publish; REST endpoint returns 400 on
  `SchemaValidationException` (never publishes invalid).
- [ ] **T-5.6** Consumer idempotency: bounded Caffeine dedupe on `X-Message-Id`
  (≈10_000 entries, TTL 1h); document POC-only.
- [ ] **T-5.7** Add a "bypass-validation" test hook on the producer to emit a deliberately
  malformed payload for the poison-message demo.
- [ ] **T-5.8** Manual `curl` scripts in README to publish malformed payloads.

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-5.1 | I | **Schema not found** in header → retried once, then lands on the matching DLQ. |
| TC-5.2 | I | **Validation failed** (poison) → straight to DLQ, **no retry** (`X-Retry-Count`=0). |
| TC-5.3 | I | **Deserialization error** → straight to DLQ. |
| TC-5.4 | I | **Registry unavailable** (transient) → retried with backoff via retry exchange. |
| TC-5.5 | I | **Downstream/business error** → retried then DLQ after max 3. |
| TC-5.6 | I | Retry path increments `X-Retry-Count`, honors TTL ladder, returns to `events.exchange`. |
| TC-5.7 | I | DLQ message has **all** spec §11 headers populated; stack trace truncated to 4KB. |
| TC-5.8 | I | Duplicate `X-Message-Id` is processed **once** (idempotency). |
| TC-5.9 | U | Producer validate-then-publish: invalid input → 400, **no** message on the exchange. |
| TC-5.10 | M | `curl` malformed-payload script lands message on the correct DLQ. |

### Acceptance Criteria (exit gate)

- [ ] **AC-5.1** All five failure modes reachable and routed per taxonomy (TC-5.1–5.5). `[SPEC §17 Phase 5]`
- [ ] **AC-5.2** Malformed payload lands on correct DLQ with all headers (TC-5.7, TC-5.10). `[SPEC-AC-5]`
- [ ] **AC-5.3** Idempotency check working (TC-5.8).
- [ ] **AC-5.4** Producer never emits invalid messages (TC-5.9). `[SPEC §11]`

---

## Phase 6 — Observability & Hardening

**Objective:** metrics, tracing, structured logging; document security enablement.

### Tasks

- [ ] **T-6.1** Emit all spec §15 metrics (cache hits/misses/size, fetch duration/failures,
  publish/consume counts + failures, DLQ depth gauge, validation failures) via Micrometer.
- [ ] **T-6.2** Expose Prometheus scrape endpoint on both services.
- [ ] **T-6.3** OpenTelemetry OTLP export (Jaeger-compatible); produce→consume spans linked
  via `X-Correlation-Id`; Apicurio fetches as child spans.
- [ ] **T-6.4** Structured JSON logging in `prod` profile; per-message INFO (publish) /
  DEBUG (consume) / WARN-ERROR (failures with headers) per spec §15.
- [ ] **T-6.5** Health endpoints: producer reports registry connectivity + pre-warm status;
  consumer reports registry connectivity, queue depths, DLQ depth.
- [ ] **T-6.6** Implement registry **fail-fast on startup** when `auto-register=OFF` and a
  pinned schema is not registered (prod faithfulness).
- [ ] **T-6.7** Document OIDC enablement for Apicurio (Keycloak) + bearer-token supplier path
  in `ApicurioClient`; keep disabled by default (spec §16).
- [ ] **T-6.8** Maven: ensure `spring-boot-maven-plugin repackage` for both services embeds
  Micrometer/OTel starters; document `mvn spring-boot:run` smoke usage.

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-6.1 | I | `/actuator/prometheus` exposes every metric named in spec §15. |
| TC-6.2 | I | Cache hit/miss counters move as expected under load. |
| TC-6.3 | I | A produce→consume cycle yields linked spans sharing the correlation id (assert via collector). |
| TC-6.4 | I | `prod` profile emits valid JSON log lines with required fields. |
| TC-6.5 | I | Producer with `auto-register=OFF` + unregistered pinned schema **fails to start** with a clear error. |
| TC-6.6 | I | Health endpoints report registry-down state correctly (toggle registry, re-check). |

### Acceptance Criteria (exit gate)

- [ ] **AC-6.1** Metrics endpoint exposes all spec §15 metrics (TC-6.1). `[SPEC-AC-8]`
- [ ] **AC-6.2** Tracing links produce↔consume (TC-6.3).
- [ ] **AC-6.3** Fail-fast on unregistered pinned schema verified (TC-6.5). `[SPEC-AC-7]`
- [ ] **AC-6.4** Registry-down resilience: cached schemas still process, WARN logged, new schemas fail gracefully. `[SPEC-AC-6]`

---

## Phase 7 — Documentation & Demo Script

**Objective:** a fresh clone runs start-to-finish in under 15 minutes.

### Tasks

- [ ] **T-7.1** Top-level README: prerequisites, `./mvnw clean install`, `docker compose up`,
  prod-faithful registration step, demo curls — under 15 minutes end-to-end.
- [ ] **T-7.2** Document the **prod-faithful compose path**: an init/one-shot service runs
  `apicurio-registry-maven-plugin:register` against the running registry, exits; services
  `depends_on` its success (default), with the dev auto-register path documented as alternative.
- [ ] **T-7.3** Sequence diagrams: produce, consume, evolution-rejected, validation-failed.
- [ ] **T-7.4** "What this POC proves" — map each deliverable to spec §1 goals.
- [ ] **T-7.5** Document all POC-only shortcuts (in-memory idempotency, single-instance
  registry, cache TTL vs evolution latency) per spec §19.
- [ ] **T-7.6** Maven command cheat-sheet (build, test, verify, `apicurio-registry:register`,
  `apicurio-registry:test`, `spring-boot:run`).
- [ ] **T-7.7** Add a short note pointing to the **deferred Gradle migration** epic (§0.4) so
  it's discoverable after the POC ships.

### Test Cases

| ID | Type | Asserts |
|----|------|---------|
| TC-7.1 | M | Fresh clone → README walkthrough completes successfully with no undocumented steps. |
| TC-7.2 | M | Prod-faithful compose path registers schemas before services start; cold `docker compose up` is all-healthy. |
| TC-7.3 | M | Every README Maven command runs as written. |

### Acceptance Criteria (exit gate)

- [ ] **AC-7.1** Fresh-clone walkthrough passes (TC-7.1). `[SPEC-AC-9]`
- [ ] **AC-7.2** Diagrams + "what this proves" present and accurate.

---

## 9. Spec Acceptance Criteria → Phase Traceability

Every spec §18 criterion must be demonstrably true. Mapping:

| Spec §18 criterion | Verified in | Test / AC |
|--------------------|-------------|-----------|
| 1. `docker compose up` healthy cold start | Phase 0 / 7 | AC-0.2, TC-7.2 |
| 2. `POST /demo/orders` + `/demo/customers` received & deserialized | Phase 2 + 3 | TC-2.3, TC-3.3 |
| 3. Two artifacts, ≥2 versions each, BACKWARD visible | Phase 4 | AC-4.1, TC-4.1 |
| 4. Incompatible v3 registration fails clearly | Phase 4 | AC-4.2, TC-4.3 |
| 5. Malformed payload → correct DLQ, all headers | Phase 5 | AC-5.2, TC-5.7 |
| 6. Apicurio stopped mid-run → cached process, new fail gracefully | Phase 6 | AC-6.4 |
| 7. `auto-register` OFF + unregistered → fail to start | Phase 6 | AC-6.3, TC-6.5 |
| 8. Metrics endpoint exposes all §15 metrics | Phase 6 | AC-6.1, TC-6.1 |
| 9. README walkthrough on fresh clone | Phase 7 | AC-7.1, TC-7.1 |

---

## 10. Build Notes, Risks & Decisions (in addition to spec §19)

- **Maven is the POC build by design (§0.1).** Registration and compatibility-gating use the
  official `apicurio-registry-maven-plugin`; no custom tooling needed.
- **Gradle migration is deferred (§0.4), not abandoned.** Tracked as a single post-POC epic;
  must not start until all §18 criteria pass on Maven. The migration's hardest piece — no
  trusted Apicurio Gradle plugin — is already understood and would be solved with custom
  SDK-backed Gradle tasks.
- **Maven daemon JVM vs Java 25 toolchain.** Maven may run on an LTS JVM while the
  toolchains plugin compiles to 25; confirm in Phase 0 (T-0.4/T-0.5) and document the
  required `toolchains.xml`.
- **Spring Boot 4.0 only.** Stay on 4.0.x; do not adopt 4.1 milestones (spec §19).
- **Surefire vs Failsafe split.** Keep Testcontainers tests as `*IT` under Failsafe so
  `mvn test` stays fast and `mvn verify` runs the full integration suite.
- **Single source of versions.** All versions in the parent POM; module POMs inherit and
  never re-declare versions.

---

*End of Maven implementation plan. Gradle migration deferred per §0.4.*
