# Contract Scaling Strategy: From 2 Contracts to 200+

> **Refactor note (June 2026):** this POC has been refactored to be **JSON Schema-only**.
> `OrderCreated` was converted from Protobuf to a JSON Schema artifact (generated POJO via
> jsonschema2pojo) and now carries a **FORWARD** compatibility rule, same as
> `CustomerRegistered`. Protobuf/BACKWARD passages below predate the refactor — treat them as
> historical/educational context; the schema files are now `order-created*.json` and the wire
> format is always `application/json`.

**Status:** Forward-looking architecture strategy (not yet implemented)
**Audience:** System architects, platform/governance owners, contract-owning teams
**Scope:** Operating model for schema-governed messaging at scale. Runtime protocol, wire
format, and the `schema-messaging-core` library are **out of scope** — they do not change.

---

## 1. Context & Problem

This POC proves end-to-end schema governance (Apicurio + RabbitMQ + Spring Boot) with **two
contracts**: `OrderCreated` (Protobuf) and `CustomerRegistered` (JSON Schema). Each contract is
implemented as its own Maven module that hand-wires a `@Configuration`/`TypeMapping` bean into
**every** service that uses it.

That model is correct and readable at two contracts. It does not survive **200+ contracts**, where
each service consumes 5–6 of them. At that scale the dominant cost is no longer *writing* a
contract — it is **managing** the fleet: onboarding, ownership, evolution, discovery, and
governance. The current design makes every one of those a linear, manual cost.

### The onboarding tax today

Adding **one** new contract currently requires edits in ~10 places:

| # | File / location | Change |
|---|-----------------|--------|
| 1 | `xyz-contracts/pom.xml` (new module) | codegen plugin + apicurio plugin + `compat-check` & `incompatible-demo` profiles (copy-pasted) |
| 2 | `pom.xml` `<modules>` | add `<module>xyz-contracts</module>` |
| 3 | `producer-service/pom.xml` | add `<dependency>` |
| 4 | `producer-service/.../config/XyzContractsConfiguration.java` | new hand-written `@Bean TypeMapping` |
| 5 | `consumer-service/pom.xml` | add `<dependency>` |
| 6 | `consumer-service/.../config/XyzContractsConfiguration.java` | duplicate of #4 |
| 7 | `producer-service/.../application.yml` | `schema.xyz.pinned-version` |
| 8 | `consumer-service/.../application.yml` | duplicate of #7 |
| 9 | `.github/workflows/schema-compat-check.yml` | extend hardcoded `-pl order-contracts,customer-contracts` |
| 10 | `.github/workflows/schema-register.yml` | extend the same hardcoded `-pl` list |
| 11 | `.github/workflows/schema-governance-bootstrap.yml` | add a hardcoded `curl` POST for the artifact's compatibility rule |

Multiply by 200 contracts, several consuming services each, and ongoing evolution. The toil is
linear and the surface for drift/error is large.

---

## 2. Root Cause

The current design **conflates three concerns** that should be separate, and packages them as one
hand-authored Maven module per contract:

1. **Schema definition** — the `.proto` / `.json` file. *The only thing a human should edit.*
2. **Consumable code artifact** — the generated Java types a service compiles against.
3. **Registration & governance tooling** — the apicurio plugin config, compat profiles, and
   compatibility-rule attachment.

Because all three are fused into one bespoke module, **every** contract re-implements the tooling,
**every** service re-implements the wiring, and the **Maven reactor — not the registry — becomes the
distribution hub**. That is backwards: Apicurio is already the source of truth.

> **Guiding principle for the target state:** A contract is *just* a **schema file + a declarative
> manifest**. Everything else — code generation, registration, rule attachment, service wiring, CI,
> and the discovery catalog — is **derived** from those two files by generic, data-driven tooling.
> Apicurio is the hub; the build system is a consumer of it, not the distribution mechanism.

---

## 3. Target Architecture — Six Pillars

### Pillar 1 — Manifest-driven contract catalog

Each contract becomes a folder of **two files**:

```
contracts/orders/order-created/
├── schema.proto          # the schema (Protobuf or JSON Schema)
└── contract.yaml         # the declarative manifest
```

```yaml
# contract.yaml
group:         events.orders
artifactId:    OrderCreated
domain:        orders
type:          PROTOBUF            # PROTOBUF | JSON
schemaFile:    schema.proto
compatibility: BACKWARD           # BACKWARD | FORWARD | FULL | NONE
routingKey:    orders.created
owner:         team-orders         # maps to CODEOWNERS
javaPackage:   com.example.contracts.orders
```

The manifest is the **single declarative input** that drives *all* of: code generation, registry
registration, compatibility-rule attachment, the CI matrix, service bean wiring, and the discovery
catalog. Managing 200 contracts becomes managing a **flat catalog of schema+manifest pairs**
processed by one generic pipeline — instead of 200 bespoke modules each re-encoding the same tooling.

```
                       ┌─────────────────────► code generation (typed jars)
                       │
 schema.proto          ├─────────────────────► registry registration (Apicurio)
      +        ──────►─┤
 contract.yaml         ├─────────────────────► compatibility-rule reconcile
   (manifest)          │
                       ├─────────────────────► CI matrix (compat-check / register)
                       │
                       ├─────────────────────► TypeMapping auto-wiring (@AutoConfiguration)
                       │
                       └─────────────────────► discovery catalog (who owns / consumes what)
```

### Pillar 2 — Central contracts repo, organized by domain, with CODEOWNERS

**Recommendation:** a dedicated **central contracts repository**, separate from service repos, laid
out by bounded context:

```
contracts/
├── orders/      <-- CODEOWNERS: @team-orders
├── customers/   <-- CODEOWNERS: @team-customers
├── billing/     <-- CODEOWNERS: @team-billing
└── ...
```

`CODEOWNERS` per domain folder preserves team autonomy (a team approves changes to its own
contracts) while keeping governance, cross-cutting compatibility checks, and the global catalog in
**one** place. See §5 for why this beats fully-distributed per-team repos.

### Pillar 3 — Distribution via domain-grouped, independently-versioned jars + a BOM

Keep strong typing (it is the core value the POC demonstrates), but **do not ship one jar per
contract** — 200 release pipelines and 200 versions is its own management nightmare.

- **Group contracts by domain** into ~15–30 artifacts: `orders-contracts`, `customers-contracts`,
  `billing-contracts`, … A service depends only on the 2–3 domain jars it actually uses.
- **Independent semantic versions** per domain jar (`orders-contracts:2.4.0`), published to an
  artifact registry (Nexus/Artifactory) — **not** lockstep `0.1.0-SNAPSHOT`. This decouples builds:
  a change to `billing-contracts` never rebuilds an orders-only service.
- A **contracts-BOM** (`contracts-bom`) pins a consistent, tested set of domain-jar versions so
  services import one BOM and omit individual versions.

```xml
<!-- service pom.xml -->
<dependencyManagement>
  <dependency>
    <groupId>com.example</groupId><artifactId>contracts-bom</artifactId>
    <version>2026.06</version><type>pom</type><scope>import</scope>
  </dependency>
</dependencyManagement>
<dependencies>
  <dependency><groupId>com.example</groupId><artifactId>orders-contracts</artifactId></dependency>
  <dependency><groupId>com.example</groupId><artifactId>customers-contracts</artifactId></dependency>
</dependencies>
```

Domain grouping matches ownership and Conway's law, keeps the jar count manageable, and reflects the
reality that a service rarely needs contracts spread across many domains.

### Pillar 4 — Auto-discovered TypeMappings (eliminate per-service boilerplate)

Each domain jar ships a **generated** Spring `@AutoConfiguration` (produced from the manifests at
build time) that contributes the domain's `TypeMapping` beans. This plugs straight into the
mechanism that **already exists** in core:

`SchemaMessagingAutoConfiguration` already injects `List<TypeMapping>` into `TypeMappingRegistry`.
Spring collects every `TypeMapping` bean on the classpath automatically. So:

- A service that adds `orders-contracts` to its classpath **auto-registers** all orders mappings.
- **Zero** per-service config code. The hand-written `OrderContractsConfiguration.java` /
  `CustomerContractsConfiguration.java` in both `producer-service` and `consumer-service` are
  deleted.
- Version pinning moves from per-contract `application.yml` keys to a single property convention
  resolved by the generated config (e.g. `schema.<domain>.pinned-version`), defaulting to latest.

### Pillar 5 — Governance-as-data

Compatibility policy lives in the manifest (`compatibility: BACKWARD`), not in copy-pasted plugin
config and hardcoded `curl` calls.

- A CI **reconcile** job reads every `contract.yaml` and applies the declared rule to Apicurio
  idempotently (`POST .../artifacts/{id}/rules`). This replaces the per-artifact `curl` blocks in
  `schema-governance-bootstrap.yml` — adding a contract needs **no** workflow edit.
- Registration and dry-run compat-check become **generic goals that iterate the manifest tree**
  rather than referencing a hardcoded `-pl order-contracts,customer-contracts` list.

> Note the POC's existing nuance survives unchanged: Protobuf `OrderCreated` → `BACKWARD`, JSON
> Schema `CustomerRegistered` → `FORWARD` (Apicurio classifies adding a JSON property as
> `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`, which BACKWARD rejects but FORWARD accepts). The manifest
> simply records this per contract.

### Pillar 6 — Dynamic CI + a discovery catalog

- **PR compat-check** builds a **matrix from changed contract folders** (a tree scan / path filter),
  not a hardcoded module list. Adding a contract requires **zero** CI edits, and only the changed
  contracts are checked.
- A generated **contract catalog** (owners, producers/consumers, current versions, compatibility
  rules — derived from manifests + Apicurio metadata) answers the question that has no answer today:
  *"What contracts exist, who owns them, and who consumes them?"* Publish it as a static site or
  markdown index in the contracts repo.

---

## 4. Type-Model Decision

| Model | Type safety | Module/release count | Best for | Verdict |
|-------|-------------|----------------------|----------|---------|
| One jar per contract | Strong | 200 jars, 200 pipelines | Maximum isolation | ❌ unmanageable |
| **Domain-grouped jars + BOM** | **Strong** | **~15–30 jars** | **JVM services, central governance** | ✅ **recommended** |
| Registry-driven / dynamic | Weak (runtime) | 0 codegen modules | Polyglot/edge/analytics consumers | ➖ hybrid only |

**Recommendation: domain-grouped typed jars + BOM as the default.** Offer registry-driven dynamic
deserialization (fetch schema from Apicurio at runtime, deserialize generically — no codegen) as a
**hybrid** for consumers that genuinely cannot use JVM codegen: polyglot services, edge ingestion,
schema-on-read analytics. First-class JVM producers/consumers stay typed.

---

## 5. Repo-Topology Decision

| Topology | Governance | Team autonomy | Global catalog/compat | Verdict |
|----------|-----------|---------------|-----------------------|---------|
| Contracts inside each service repo | Fragmented | High | Very hard | ❌ |
| Per-team contract repos | Fragmented | High | Hard (N repos to aggregate) | ➖ |
| **Central contracts repo + CODEOWNERS** | **Central** | **Preserved via CODEOWNERS** | **Native** | ✅ **recommended** |

A **central contracts repo with per-domain CODEOWNERS** is the pragmatic middle: one place for
governance, cross-cutting compatibility checks, and the catalog, while each team still owns and
approves its own domain folder. Fully-distributed per-team repos fragment governance and make a
global catalog and cross-contract compat story expensive to assemble.

---

## 6. Onboarding: Before vs After

| | Today | Target |
|---|-------|--------|
| Human edits | ~10 files across modules, services, CI, governance | **Drop 2 files** in `contracts/<domain>/<name>/` |
| Code artifact | New Maven module, hand-authored pom | Generated into the domain jar |
| Service wiring | 2 hand-written `@Configuration` classes | Auto-wired from the domain jar on the classpath |
| Registration & rule | Edit register workflow + add a `curl` | Derived from `contract.yaml` by generic CI |
| CI inclusion | Edit two hardcoded `-pl` lists | Picked up automatically by the matrix |
| Discoverability | None | Appears in the generated catalog |

---

## 7. Migration Path (phased, non-breaking)

Each phase is independently shippable and leaves the POC working.

- **P1 — Introduce manifests.** Add a `contract.yaml` next to each of today's two schemas. No
  behavior change; purely additive metadata that later phases consume.
- **P2 — Generate the wiring.** Generate `TypeMapping` `@AutoConfiguration` from the manifests; then
  **delete** the four hand-written `*ContractsConfiguration.java` classes (producer + consumer). Net
  reduction in code with identical runtime behavior.
- **P3 — Domain jars + BOM.** Collapse `order-contracts` and `customer-contracts` into
  domain-grouped jars (here, one each), introduce `contracts-bom`, switch both services to
  BOM-pinned dependencies and independent semantic versions.
- **P4 — Data-driven CI & governance.** Make compat-check/register **matrix-driven from the manifest
  tree** (drop the hardcoded `-pl`); add a **governance reconcile** job that applies compatibility
  rules from manifests (drop the hardcoded `curl` blocks).
- **P5 — Catalog & extraction.** Generate the discovery catalog. When contract ownership spreads
  across teams, extract `contracts/` into its own repository with per-domain CODEOWNERS.

---

## 8. What Stays the Same

This is an **organization / build / distribution** change, **not** a runtime-protocol change.
Unaffected:

- `schema-messaging-core`: `SchemaResolver` (Caffeine cache, stale-on-outage),
  `SchemaAwareMessageConverter`, `TypeMappingRegistry`, `ApicurioClient`, the
  `SerializationStrategy` SPI.
- The **wire format** (raw bytes + `X-Schema-*` headers).
- The **failure model** (DLX/DLQ, retry TTL ladder, exception taxonomy).
- Apicurio as the source of truth, and BACKWARD/FORWARD semantics per artifact.

The `List<TypeMapping>` injection point in core is, in fact, the seam that makes Pillar 4 free.

---

## 9. Risks & Non-Goals

- **Dynamic resolution loses compile-time safety.** Confine it to the hybrid edge cases in §4; keep
  JVM services typed.
- **BOM discipline.** Independent versioning only pays off if the contracts-BOM is curated and
  released on a predictable cadence; otherwise services drift. Treat the BOM as a governed product.
- **Generated-code review.** Generated `@AutoConfiguration` and domain jars must be reproducible and
  diffable in CI so reviewers trust them.
- **Non-goal: Gradle.** Maven remains the build (Gradle migration stays deferred post-POC per plan
  §0.4). The strategy is build-tool-agnostic but assumes Maven.
- **Non-goal: changing the runtime protocol.** See §8.
