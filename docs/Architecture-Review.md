# Architecture Review — Java / Spring Boot Best Practices

> **Refactor note (June 2026):** this POC has been refactored to be **JSON Schema-only**.
> `OrderCreated` was converted from Protobuf to a JSON Schema artifact (generated POJO via
> jsonschema2pojo) and now carries a **FORWARD** compatibility rule, same as
> `CustomerRegistered`. Protobuf/BACKWARD passages below predate the refactor — treat them as
> historical/educational context; the schema files are now `order-created*.json` and the wire
> format is always `application/json`.

**Reviewer:** Senior architecture review
**Scope:** Project & module architecture, inter-module / inter-service communication, dependency
management (Java + Spring Boot ecosystem).
**Verdict:** A genuinely high-quality codebase. The recommendations below are what an
*exemplary reference project* would add on top of an already-solid base — they are refinements,
not rescues. Items are ranked by impact/effort in [§7](#7-prioritized-recommendations).

> Emphasis (per request): **module architecture**, **inter-module/service communication**, and
> **dependency management** are treated in depth (§3–§5). Other areas (§6) are summarized so the
> review is complete without diluting the focus.

---

## 1. Executive summary

The build is governed (single-source versions, BOM import, JDK-25 toolchain, a machine-enforced
`core ↛ contracts` layering rule), the code is idiomatic modern Java (records, constructor
injection, validated `@ConfigurationProperties`, a clean exception taxonomy), and the messaging
core is resilient (Caffeine stale-on-failure caching, an SPI for serialization strategies). The
schema registry *is* the inter-service contract, gated in CI — a standout strength.

The gaps that separate "very good POC" from "exemplary reference" cluster in three places:
**(a)** layering is enforced only at the Maven-coordinate level, not at the package/import level;
**(b)** shared AMQP infrastructure constants are **duplicated three ways** with no single source
of truth; **(c)** dependency management centralizes *external* versions beautifully but not the
project's *own* modules, and the enforcer is under-used.

### Scorecard

| Area | Current state | Highest-leverage gap |
|---|---|---|
| Module architecture & boundaries | **Strong** — domain-agnostic core, enforced ban, SPI design | No test-time (import-level) layering check; library exposes all internals |
| Inter-module / service communication | **Strong** — schema-as-contract, registry compat gate | Shared exchange should be split per-domain (also removes the triplicated constants) |
| Dependency management | **Good** — single-source external versions, BOM import | Own modules not in `dependencyManagement`; enforcer used for one rule only |
| Build-quality tooling | **Absent** | No Spotless / static analysis / JaCoCo / `.editorconfig` |
| Testing | **Good** — Testcontainers, AssertJ, real DLX/retry coverage | No auto-config slice tests; several core classes untested |
| CI | **Partial** — schema modules only | Services never compiled/tested in CI |
| Security / observability | **POC-deferred (by design)** | Out of scope for a *reference* repo; noted in §6 |

---

## 2. What's already exemplary (keep doing this)

These are correct and worth calling out so they are preserved through any refactor:

- **Single source of versions** — all external versions live in the parent `pom.xml`
  `<properties>` + `<dependencyManagement>`, with the Spring Boot 4.0 BOM imported (`pom.xml:50`).
  Module POMs never re-declare external versions.
- **Machine-enforced layering** — `maven-enforcer` `bannedDependencies` forbids
  `schema-messaging-core` from depending on either contracts module
  (`schema-messaging-core/pom.xml:88`). The domain-agnostic core principle is enforced, not just
  documented. *(Verified: core does **not** import either contracts class.)*
- **JDK-25 toolchain pinned** for every module via `maven-toolchains-plugin` + compiler
  `<release>25</release>` (`pom.xml:140`).
- **Clean test split** — Surefire (`*Test.java`, fast/mock) vs Failsafe (`*IT.java`,
  Testcontainers) wired at the parent (`pom.xml:102`).
- **Idiomatic modern Java** — records for DTOs/value objects/config, constructor injection
  throughout, `final` fields, `Optional` at boundaries, switch expressions, pattern matching.
- **Validated type-safe config** — `ApicurioCacheProperties` is a record with a compact
  constructor enforcing defaults; `Duration` types, not strings.
- **Resilient core** — `SchemaResolver` Caffeine cache with TTL + refresh-after-write and
  stale-on-failure fallback; only throws `RegistryUnavailableException` when nothing is cached.
- **Extensible by design** — `SerializationStrategy` SPI (`ProtobufStrategy`,
  `JsonSchemaStrategy`); each contracts module contributes a `TypeMapping` bean and owns its AMQP
  topology via `META-INF/spring/...AutoConfiguration.imports`.
- **Schema as the governed contract** — both artifacts registered with compatibility rules; the
  `compat-check` profile is the CI merge gate.

---

## 3. Module architecture & boundaries  *(primary focus)*

### 3.1 Add import-level layering enforcement (ArchUnit)
The `bannedDependencies` rule catches only Maven **coordinates**. It would not catch a stray
`import com.example.contracts...` inside core if the classpath ever made it reachable, nor does
it police *intra-module* layering (e.g. a controller reaching into `...registry` internals) or
package cycles.

**Recommendation:** add an `ArchUnit` test in `schema-messaging-core` (and one in each service)
asserting:
- no package under `...messaging.core` imports `...contracts..`;
- `...producer.controller` / `...consumer.listener` do not import registry/serde internals;
- no package cycles (`slices().matching("...core.(*)..").should().beFreeOfCycles()`).

This is the test-time complement to the existing build-time ban — together they make the
architecture *executable documentation*.

### 3.2 Separate published API from internals
`schema-messaging-core` is a reusable **library jar**, but every class is `public` — consumers
can bind to `SchemaResolver`, `ApicurioClient`, `DlxMessageRecoverer`, etc., which are
implementation details. An exemplary library publishes a deliberate surface.

**Recommendation (pick one):**
- *Lightweight:* package convention — `...core.api` (exported, e.g. `EventPublisher`,
  `SerializationStrategy`, `TypeMapping`, the exception types) vs `...core.internal` (everything
  else), enforced by the ArchUnit rule in §3.1.
- *Strict (fits Java 25):* a `module-info.java` with explicit `exports`, turning the API boundary
  into a compiler-checked contract.

### 3.3 Test the auto-configurations as starters
The module is effectively a Spring Boot starter (`SchemaMessagingAutoConfiguration`,
`SchemaMessagingConsumerAutoConfiguration`, plus per-contract topology auto-configs) relying on
`after=` ordering and `@ConditionalOnMissingBean` overrides. None of this is unit-tested.

**Recommendation:** add `ApplicationContextRunner` slice tests per auto-config asserting bean
presence, that user `@ConditionalOnMissingBean` overrides win, and that ordering holds. This is
the canonical Spring Boot way to test starter modules and protects the most fragile wiring.

---

## 4. Inter-module & inter-service communication  *(primary focus)*

### 4.1 Standout strength — the schema is the contract
Producer ↔ consumer never share a Java payload type directly; they agree on a **registry-governed
schema** carried in `X-Schema-*` headers, with BACKWARD/FORWARD compatibility gated in CI. This
is exactly the right inter-service coupling for event-driven systems. Keep it central in the
narrative of the reference project.

### 4.2 Split the shared exchange into per-domain exchanges
**Current state.** Both domains route through a *single shared* topology:
`OrderEventTopologyAutoConfiguration.java:33` and its customer twin each declare the **same**
`events.exchange` / `events.dlx` / `events.retry.exchange`, deduplicated at runtime via
name-based `@ConditionalOnMissingBean`. Domains are differentiated only by **routing key**
(`orders.created` vs `customers.registered`). Because the exchanges are shared, their names are
triplicated as a *symptom*: byte-identical `EventExchanges` in both contracts modules (verified by
`diff`) plus a third copy as `@Value("${events.dlx:events.dlx}")` defaults in
`SchemaMessagingConsumerAutoConfiguration.java:37`.

**Intended design (decided): per-domain exchanges.** Each contracts module owns its own exchange
set — `orders.exchange` / `orders.dlx` / `orders.retry.exchange`, and the customer equivalents.

**Why this is the right call:**
- **Domain isolation / blast-radius containment** — a customer-side topology change or message
  flood cannot affect order processing.
- The exchange names become **genuinely domain-specific**, so they correctly live in each
  contracts module — there is nothing left to centralize. The triplicated-constants concern
  **dissolves on its own** (distinct names, not duplicated ones), and the
  `@ConditionalOnMissingBean` shared-exchange dedup dance disappears (no two modules declare the
  same exchange).

**Key consequence — this is more than a rename.** The core `DlxMessageRecoverer` is a single
domain-agnostic bean hardcoded with **one** `dlxExchange` / `retryExchange`
(`SchemaMessagingConsumerAutoConfiguration.java:37-41`, used at `DlxMessageRecoverer.java:57,63`);
it routes by routing key, not by exchange. Per-domain exchanges require it to resolve the **correct
domain's** DLX/retry exchange per message **without** re-coupling core to specific domains.
Recommended mechanism: have each contracts module register its DLX/retry exchange names through a
small SPI mapping — mirroring the existing `TypeMapping` / `TypeMappingRegistry` pattern the core
already uses for serialization — or carry the target exchange in a message header set at publish
time. Either keeps core domain-agnostic while letting the recoverer pick the right exchange.

**Separate, minor item:** `RetryTopologyFactory` is duplicated *logic* (not constants) across both
contracts modules. Under per-domain exchanges it either stays per-module or moves to
`schema-messaging-core` as a domain-agnostic helper — a small, independent decision (note that
moving it would add a compile dependency on core to the contracts modules, which today depend
"only on protobuf-java / Jackson" per CLAUDE.md).

The domain-specific routing keys/queue names already live correctly per-module in
`OrderEventRouting` / `CustomerEventRouting` (the `routing-config-separation` work) and stay as-is.

### 4.3 Add a consumer-driven contract test (additive)
The registry compat gate guards *schema* evolution but not the *producer's actual emitted
payload shape vs the consumer's expectation*. **Recommendation:** add a Pact (or Spring Cloud
Contract) test as a complementary guard. This is additive — it does not replace the registry
gate; it catches "producer changed which fields it populates" independent of schema compatibility.

### 4.4 Assert the wire-format/header contract once, shared
The `X-Schema-*` / `X-Message-Id` / `X-Correlation-Id` header set and content-type rules are
documented in `CLAUDE.md` but only exercised incidentally inside integration tests.
**Recommendation:** a small shared contract test fixture over `SchemaMessageHeaders` asserting the
exact header set and content-types, so the wire format is pinned in one authoritative test.

---

## 5. Dependency management  *(primary focus)*

### 5.1 Centralize the project's *own* module versions
External versions are perfectly centralized, but each service repeats
`<version>${project.version}</version>` for all three internal modules
(`producer-service/pom.xml:22,29,36`; same in the consumer).

**Recommendation:** declare the internal modules in the parent `<dependencyManagement>` so child
POMs omit the version entirely — the same discipline already applied to external libraries. For a
larger reference project, a dedicated `schema-registry-demo-bom` module is the textbook pattern;
for five modules, parent `dependencyManagement` is sufficient and simpler.

```xml
<!-- parent pom.xml, inside <dependencyManagement><dependencies> -->
<dependency>
  <groupId>com.example</groupId>
  <artifactId>schema-messaging-core</artifactId>
  <version>${project.version}</version>
</dependency>
<!-- ...order-contracts, customer-contracts likewise -->
```

### 5.2 Promote `maven-enforcer` to a parent-level guardrail
Enforcer is configured but used for exactly one rule (the contracts ban in core). An exemplary
build adds a parent-level execution every module inherits:
- `dependencyConvergence` + `requireUpperBoundDeps` — no conflicting transitive versions;
- `banDuplicatePomDependencyVersions` — catches accidental re-declared versions;
- `requireMavenVersion` (≥ the wrapper's 3.9.x) + `requireJavaVersion`;
- `reproducibleBuilds`.

Keep the core's contracts-ban as an additional module-local rule.

### 5.3 Reproducible, publishable library POMs
`schema-messaging-core` and the contracts modules are consumable libraries whose installed POMs
carry `${project.version}` / property-driven versions. **Recommendation:** add
`flatten-maven-plugin` so installed/deployed POMs are fully resolved and self-contained —
important since this repo is meant to be *referenced* and its artifacts reused.

### 5.4 Supply-chain hygiene
- **SBOM:** add the CycloneDX Maven plugin to emit an SBOM per build.
- **Controlled upgrades:** enable Dependabot (or `versions-maven-plugin`) — the project pins exact
  versions, so it needs a deliberate upgrade mechanism.
- **Build reproducibility:** pin Maven/JVM flags via `.mvn/maven.config` and `.mvn/jvm.config`
  (today only `maven-wrapper.properties` exists).

### 5.5 Close the CI gap
Existing workflows build only the contracts modules (schema register / compat-check). The
**services are never compiled or tested in CI**. **Recommendation:** add a `build-and-test`
workflow running `./mvnw verify` across all modules (Testcontainers covers the integration side),
so service regressions are caught pre-merge.

---

## 6. Cross-cutting Java / Spring polish  *(secondary — summarized)*

- **Build-quality tooling is entirely absent.** For a reference repo, add: **Spotless**
  (deterministic formatting + import ordering), one static-analysis tool
  (**SpotBugs** or **PMD**/Checkstyle), **JaCoCo** with a coverage ratchet, and an `.editorconfig`.
- **`ResolvedSchema.rawContent()` returns a mutable `byte[]`** — a real but defensible hot-path
  trade-off (defensive copies on every resolve would cost). Document the invariant ("treat as
  read-only") rather than "fix" it.
- **Virtual-threads opportunity (Java 25):** `CachePreWarmer` pre-warms schemas serially on
  startup; a `newVirtualThreadPerTaskExecutor()` fan-out is a clean, on-topic Java 25 showcase.
- **Test gaps:** `IdempotencyFilter`, `DlxRoutingAdvice` (AOP), `ApicurioClient` exception
  mapping (404→`SchemaNotFoundException`, etc.), and both health indicators have no unit tests.
- **Production-hardening, *not* reference-architecture (safe to skip for a teaching repo):**
  Spring Security on `/api/*`, enabling the already-supported OIDC to Apicurio, Micrometer
  metrics. Listed for completeness; out of scope for an exemplary-*reference* goal.

---

## 7. Prioritized recommendations

Impact/Effort are relative; "Why" frames each against the *exemplary reference project* goal.

| # | Recommendation | Area | Impact | Effort | Mechanism / why it matters |
|---|---|---|---|---|---|
| 1 | Internal modules in parent `dependencyManagement` | Dep mgmt | High | Low | Removes repeated `${project.version}`; one source of truth for internal versions (§5.1) |
| 2 | ArchUnit layering + cycle tests | Architecture | High | Low–Med | Executable architecture; complements the Maven-coord ban at import level (§3.1) |
| 3 | Strengthen enforcer (convergence, reproducible builds) at parent | Dep mgmt | High | Low | Inherited guardrails against transitive drift (§5.2) |
| 4 | Split shared exchange into per-domain exchanges (orders/customers); adjust core recoverer to resolve DLX/retry per domain | Inter-module | High | Med–High | Domain isolation; exchange names become per-module so the triplication dissolves; touches the core `DlxMessageRecoverer` single-exchange assumption (§4.2) |
| 5 | Auto-config `ApplicationContextRunner` slice tests | Architecture | Med–High | Med | Canonical way to test starter wiring; protects the most fragile code (§3.3) |
| 6 | Full-build CI workflow (`./mvnw verify`, all modules) | Dep mgmt / CI | High | Low | Services are currently never built in CI (§5.5) |
| 7 | Spotless + JaCoCo + `.editorconfig` | Tooling | Med | Low | Table-stakes hygiene for a repo meant to be copied (§6) |
| 8 | API vs internal encapsulation (package convention or JPMS) | Architecture | Med | Med | A library should publish a deliberate surface (§3.2) |
| 9 | CycloneDX SBOM + Dependabot + flatten-maven-plugin | Dep mgmt | Med | Low–Med | Supply-chain hygiene; clean publishable POMs (§5.3–§5.4) |
| 10 | Consumer-driven contract test (Pact / Spring Cloud Contract) | Inter-service | Med | Med | Guards payload-shape expectation beyond schema compatibility (§4.3) |

---

*This review changed no source or build files. Every "current state" claim above is grounded in a
file read during the review; paths are cited inline for verification.*
