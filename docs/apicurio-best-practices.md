# Apicurio Registry — Best Practices: Dev vs. Production, Maven Plugin vs. REST API

> **Audience:** Engineers operating a schema registry (Apicurio specifically, but most of this
> applies to Confluent Schema Registry / AWS Glue / Redpanda too). It covers how to use the
> registry across the lifecycle — local dev, CI, and production — and when to reach for the
> **Maven plugin** vs. the **REST API**. The last section maps every recommendation to the
> exact place this repo already implements it.

---

## Table of contents

1. [Mental model — two orthogonal decisions](#1-mental-model--two-orthogonal-decisions)
2. [The three integration surfaces](#2-the-three-integration-surfaces)
3. [Maven plugin vs. REST API — decision table](#3-maven-plugin-vs-rest-api--decision-table)
4. [Development phase](#4-development-phase)
5. [Production phase (the critical section)](#5-production-phase-the-critical-section)
6. [How other organizations run this](#6-how-other-organizations-run-this)
7. [This project, mapped to the practices](#7-this-project-mapped-to-the-practices)
8. [Registering via the Apicurio UI (with authentication)](#8-registering-via-the-apicurio-ui-with-authentication)
9. [How contracts modules get the schema & generate code](#9-how-contracts-modules-get-the-schema--generate-code)
10. [Optimizing the CI & local schema-check footprint](#10-optimizing-the-ci--local-schema-check-footprint)
11. [Sources](#11-sources)

---

## 1. Mental model — two orthogonal decisions

Most confusion about "how should we use the registry" comes from collapsing two independent
decisions into one. Keep them separate:

| Decision | The question | The options |
|----------|-------------|-------------|
| **(A) Who registers schemas?** | What process is *allowed to publish* a schema version? | A CI/CD pipeline (controlled), or the application itself at runtime (`auto-register`). |
| **(B) How do you talk to the registry?** | What client/transport do you use to register, gate, or fetch? | Maven plugin, REST API, or a SerDes/client library. |

These are orthogonal: you can register **content** with the Maven plugin while configuring
**rules** over REST; you can fetch schemas at runtime via a client library while a pipeline
(not the app) owns registration. The rest of this document is organized around getting both
decisions right for each phase.

The single most important rule, stated up front:

> **In production, the answer to (A) is always "a CI/CD pipeline," never "the app."**
> Applications get *read-only* access. Schema publication is a governed, reviewed event.

---

## 2. The three integration surfaces

There are three distinct ways code touches Apicurio. They are not interchangeable — each owns
a different job.

### 2.1 Maven plugin — `apicurio-registry-maven-plugin`

Build/CI-time, **JVM-only**. Useful goals:

- `register` — publish schema *content* (one or more artifacts/versions).
- `test` — run a **compatibility check** against what's already registered (the merge gate).
  In v3 you can equivalently use `register` with `<dryRun>true</dryRun>` to validate without
  writing.
- `download` — pull schemas at build time (e.g. to generate code from the registry).

**Best for:** registering schema content and gating pull requests in JVM/Maven projects. It is
the officially supported, trusted path for compatibility gating in a Maven build — there is no
equally trusted Gradle equivalent today, which is why this POC standardizes on Maven (see
`CLAUDE.md` §0.4 / build conventions).

### 2.2 REST API v3 — `/apis/registry/v3/...`

Language-agnostic, covers both runtime and admin operations. Examples this repo uses:

```bash
# Attach a BACKWARD compatibility rule to an artifact (governance config)
curl -X POST http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'
```

**Best for:** everything the Maven plugin doesn't model — **rules and global config**,
bootstrap/automation scripts, polyglot organizations (Go/Python/Node services), and operational
tasks (listing artifacts, managing versions/branches, admin export/import). The REST API is the
universal escape hatch.

### 2.3 SerDes / client library

Runtime serialize/deserialize and schema *resolution* on the hot path. This is a **data-plane**
concern, not a registration pipeline. Its key governance knob is `auto.register.schemas`
(Confluent SerDes) / the equivalent auto-register behavior in Apicurio SerDes. In this repo the
runtime data plane is hand-rolled (`SchemaAwareMessageConverter` + `SchemaResolver` +
`ApicurioClient`) rather than the Kafka SerDes, but the principle is identical: at runtime you
*fetch and validate*, you do not *publish*.

---

## 3. Maven plugin vs. REST API — decision table

The guiding rule:

> **Maven plugin for schema content + compatibility gating. REST API for rules, admin, and
> anything cross-language.**

| Task | Use | Why |
|------|-----|-----|
| Register a schema version in CI (JVM project) | **Maven plugin** (`register`) | First-class, declarative in the POM, versioned with the build. |
| PR merge gate / compatibility check | **Maven plugin** (`test`, or `register -DdryRun`) | Fails the build on an incompatible change; trusted, reproducible. |
| Generate code from a registered schema | **Maven plugin** (`download`) or local `protoc` | Build-time concern. |
| Attach/modify compatibility rules (BACKWARD, etc.) | **REST API** | Rules are not schema content; the plugin doesn't own them. Set once at bootstrap. |
| One-time governance bootstrap of a persistent registry | **REST API** (script / `workflow_dispatch`) | Idempotent admin op; re-running returns HTTP 409, which is success. |
| Register from a non-JVM service (Go/Python/Node) | **REST API** or that language's SerDes | The Maven plugin is JVM-only. |
| Runtime fetch/validate on the message path | **Client library / SerDes** | Hot path, not a build step. |
| List versions, inspect, export/import, admin | **REST API** | Operational surface the plugin doesn't expose. |

The clean division that falls out of this — and that this repo already follows — is:
**plugin publishes the *content*, REST configures the *rules*.**

---

## 4. Development phase

The goal in dev is fast iteration without teaching bad habits that don't survive to prod.

- **A throwaway local registry can use auto-register** for the tightest possible inner loop —
  but treat it as a convenience, not the contract. Never let "it worked locally because the app
  registered its own schema" become how schemas reach a shared registry.
- **Prefer explicit registration even locally** so dev mirrors prod. Seed a local registry with
  the same command CI uses:

  ```bash
  ./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
    -Dapicurio.registry.url=http://localhost:8080
  ```

- **Run the compatibility gate locally before pushing** to get the same answer CI will give:

  ```bash
  ./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check
  ```

- **Use a "prod-faithful" local bootstrap.** This repo's `schema-registrar` container in
  `docker-compose.yml` runs the exact `register` command, then attaches the BACKWARD rules over
  REST, then exits (`restart: "no"`). That mirrors the CI/prod pipeline locally far better than
  letting the apps auto-register, and it's the recommended local pattern.
- **Keep the test split honest.** Fast, mock-based checks gate every change; registry-backed
  integration checks run on `verify`. Don't move registry calls onto the fast path.

---

## 5. Production phase (the critical section)

Production is where the orthogonal decisions from §1 stop being a matter of taste.

### 5.1 Apps must not register schemas

Set `auto.register.schemas=false` (Confluent SerDes) / disable auto-register in the Apicurio
SerDes / keep your client **fetch-only**. This is the strongest, most universally repeated
production recommendation across the ecosystem. An app that can register a schema can
silently introduce a breaking change at 3 a.m. with no review.

### 5.2 Registration happens only through CI/CD ("schemas as code")

Schema files live in git. A change is a pull request. The PR runs a compatibility gate. On
merge to `main`, a pipeline — not a human, not an app — registers the new version. This is the
dominant industry pattern (often called **schemas-as-code** or schema GitOps), and it gives you
review, audit, rollback, and a single uniform registration path across every team.

### 5.3 Enforce compatibility server-side, not in client config

Attach the compatibility rule (`BACKWARD` here) to the artifact (or as a global registry rule)
**in the registry itself**, set once at bootstrap via REST. Server-side rules cannot be bypassed
by a misconfigured client. Client-side `use.latest.version` / version pinning is about *which*
schema a producer serializes with — it is not a substitute for a server-enforced rule.

### 5.4 Pin versions; decouple schema lifecycle from app deploy

Producers should pin a known-good schema version (or use a deliberate `use.latest.version`
policy), so a registry change can't shift serialization under a running service. A schema's
lifecycle (propose → review → gate → register) is separate from, and usually ahead of, the
deploy that starts using it.

### 5.5 Authentication & authorization

Turn on auth (OIDC). Grant **write** to exactly one identity — the CI service account — and
**read-only** to application service accounts. The asymmetry enforces §5.1 at the IAM layer, not
just by convention.

### 5.6 High availability & operations

Run the registry on durable SQL storage (Postgres) with multiple replicas, back up the database,
expose health checks, and scrape registry metrics. The registry is on the critical path for
producing/consuming governed messages — treat its availability accordingly, and make sure
clients degrade gracefully (e.g. serve a cached schema, fail-fast on a missing *pinned* schema)
when it's briefly unavailable.

---

## 6. How other organizations run this

- **Schemas-as-code / GitOps is the norm.** Mature teams keep schemas in version control, gate
  every change with a compatibility check in CI, and register on merge. The registry is the
  runtime source of truth; git is the change-management source of truth. This small POC is a
  faithful miniature of that pattern.
- **Confluent-ecosystem defaults.** The widely cited Confluent guidance: set
  `auto.register.schemas=false` in production and register out-of-band; **enable schema
  normalization** so syntactically-different-but-semantically-equal schemas don't create
  spurious new versions; and choose a deliberate **subject-naming strategy**
  (`TopicNameStrategy` vs. `RecordNameStrategy` vs. `TopicRecordNameStrategy`) based on whether a
  topic carries one or many event types.
- **Apicurio adopters learn the same lesson.** Teams that started with runtime auto-register
  (e.g. the CROZ writeup) found it didn't scale — it gave no *uniform* way to register schemas
  across many projects — and standardized on the **official Maven plugin plus a central
  registration pipeline**. Polyglot shops that can't use a JVM plugin everywhere lean on the
  **REST API** and per-language SerDes instead.
- **Migration is low-friction.** Apicurio is wire-compatible with the Confluent SerDes format,
  so organizations can move between Apicurio and Confluent (or run a Confluent-compatibility API
  on Apicurio) without rewriting producers/consumers.

---

## 7. This project, mapped to the practices

Every recommendation above is already demonstrated somewhere in this repo. Use this as both a
correctness check and an onboarding map.

| Practice | Where it lives in this repo |
|----------|------------------------------|
| **Schema source of truth = committed files** (git-first; see §9) | `*-contracts/src/main/resources/schemas/*.proto` / `*.json` — codegen reads these; the registry is fed from the same files. The registry is never the codegen source. |
| Maven plugin registers schema **content** | `order-contracts/pom.xml`, `customer-contracts/pom.xml` — `apicurio-registry-maven-plugin` `register` goal (v1 baseline + v2 backward-compatible `promo_code` addition, `ifExists=FIND_OR_CREATE_VERSION`). |
| Maven plugin as the **compat merge gate** | `compat-check` profile in both contracts POMs — `register` with `<dryRun>true</dryRun>` bound to `verify`. `incompatible-demo` profile proves rejection. |
| REST API configures **rules** (not content) | `docker-compose.yml` `schema-registrar` and `.github/workflows/schema-compat-check.yml` — `curl POST .../artifacts/{id}/rules` attaches the `BACKWARD` rule. |
| **Schemas-as-code** CI gate on PRs | `.github/workflows/schema-compat-check.yml` — self-contained Apicurio+Postgres, registers a baseline, attaches rules, runs the per-domain dry-run check. Blocks merge on INCOMPATIBLE. |
| Registration **on merge**, not by the app | `.github/workflows/schema-register.yml` (push to `main`) → registers to the shared dev registry. |
| One-time governance **bootstrap over REST** | `.github/workflows/schema-governance-bootstrap.yml` (`workflow_dispatch`) — attaches `BACKWARD` to each artifact in a persistent registry; HTTP 409 on re-run = already-applied = success. |
| **Prod-faithful local** bootstrap | `docker-compose.yml` `schema-registrar` (`restart: "no"`) runs the real `register` + REST rule calls, instead of app auto-register. |
| Apps are **fetch-only** at runtime | `ApicurioClient` exposes only `fetchByGlobalId` / `fetchByCoordinates` / `latestVersion`; producer/consumer run with `auto-register=OFF`. |
| **Version pinning** + fail-fast | `schema.{orders,customers}.pinned-version` in `application.yml`; an unregistered pinned schema fails on startup. |
| **Auth** hook for production | `ApicurioClient` accepts a bearer-token supplier (OIDC), off by default — the seam to enable §5.5. |
| Graceful degradation when registry is down | `SchemaResolver` Caffeine cache serves stale on outage; throws only when nothing is cached. |

**One thing to verify before calling it production-ready:** §5.5 (auth) is currently a *hook*,
not enabled. For a real deployment, turn on OIDC and split write (CI) vs. read-only (apps)
credentials so the "apps never register" rule is enforced by IAM, not just convention.

---

## 8. Registering via the Apicurio UI (with authentication)

The Apicurio web UI is convenient, and it *can* register schemas. The question is not "can I?"
but "*should this be how schemas get into the registry?*" — and the answer depends on the role
of the UI in your workflow.

### 8.1 How UI authentication works

Apicurio 3.x secures both the API and the UI with **OIDC** (commonly Keycloak). The UI login
*is* the OIDC flow — a user signs in to the identity provider and the UI calls the API with the
resulting token. Auth is turned on with `APICURIO_AUTH_*` settings on the registry (and the UI
container), e.g.:

```yaml
# docker-compose.yml — registry service (illustrative; not wired in this repo yet)
environment:
  APICURIO_AUTH_ENABLED: "true"
  APICURIO_AUTH_SERVER_URL: https://keycloak.example.com/realms/apicurio
  APICURIO_AUTH_CLIENT_ID: registry-api
  APICURIO_REST_DELETION_ARTIFACT_ENABLED: "false"   # tighten destructive ops in shared envs
```

Apicurio ships a **role-based access** model. The three built-in roles:

| Role | Can do | Give it to |
|------|--------|-----------|
| `sr-admin` | Everything, incl. rules, deletes, global config, import/export | A small number of platform owners |
| `sr-developer` | Read + create/update artifacts and versions | Teams that legitimately publish (ideally only via CI service accounts) |
| `sr-readonly` | Read/inspect only | **Default** for humans and for every application service account |

### 8.2 When the UI is the right tool

- **Inspecting** artifacts, versions, and the rendered schema.
- **Reading** the effective compatibility rules and global config.
- An **emergency** manual rule change by an admin (e.g. temporarily relaxing a rule), recorded
  and reverted afterward.
- **Ad-hoc exploration** on a throwaway local/dev registry.

### 8.3 Why UI registration shouldn't be your source of truth

A schema created by clicking "Upload" in the UI has **no git history, no PR, no review, and no
CI compatibility gate**. It silently breaks the "schemas as code" model that §5–§6 are built
on: the next CI run may not even know about it, and you've lost the audit trail. So:

- Keep human accounts (and all app accounts) at **`sr-readonly`** by default.
- Restrict `sr-developer`/`sr-admin` to a few people, and prefer a **CI service account** as the
  only routine writer.
- Treat the UI as **read/inspect by default; CI is the writer.** Manual UI registration is an
  acceptable break-glass action, not a workflow.

> **For this repo:** auth isn't enabled yet (no OIDC in `docker-compose.yml`). The block above
> is the seam to add it. The registry's bearer-token support is already reflected in
> `ApicurioClient`'s token supplier — the runtime client is ready for an authenticated registry.

---

## 9. How contracts modules get the schema & generate code

This is a genuine architectural fork: **is git or the registry the source of truth for the
schema your code is generated from?** Both work; they have different tradeoffs.

### 9.1 Model A — git-first (what this repo does today, recommended)

The committed schema files are the source of truth. The build generates code **from those local
files**, and the *same* files are pushed to the registry separately.

```
src/main/resources/schemas/order-created.proto   (committed, the source of truth)
        │
        ├─►  protobuf-maven-plugin / jsonschema2pojo-maven-plugin  ──►  generated Java classes
        │
        └─►  apicurio-registry-maven-plugin :register             ──►  registry version
```

In this repo:
- `order-contracts/pom.xml` → `protobuf-maven-plugin` generates from
  `src/main/resources/schemas/order-created.proto`.
- `customer-contracts/pom.xml` → `jsonschema2pojo-maven-plugin` generates from
  `src/main/resources/schemas/customer-registered.json`.

**Pros:** hermetic, offline, reproducible builds; reviewable schema diffs in PRs; the generated
code matches exactly what the compat gate checked; no build-time dependency on a live registry.
This is the dominant industry pattern.

**Cons:** the schema lives in this repo, so non-JVM/other-team consumers must either copy the
file or read it from the registry — git is canonical only for *this* codebase.

### 9.2 Model B — registry-first (optional)

The schema is authored/registered first (via UI or API/CI), and the contracts module **pulls it
from the registry at build time** with the `download` goal, then generates code from the
downloaded file.

```
registry artifact (events.orders/OrderCreated, pinned version)
        │ apicurio-registry-maven-plugin :download  (generate-sources)
        ▼
target/schemas/order-created.proto
        │ protobuf-maven-plugin
        ▼
generated Java classes
```

**Pros:** the registry is the single canonical source across many teams, including non-JVM ones.
**Cons:** the build now requires a **reachable, authenticated** registry; you lose offline and
hermetic builds; and generated code can drift from git unless you **pin a specific version**.

#### Ready-to-use recipe (illustrative — not wired into this repo)

Bind `download` to `generate-sources` so it runs **before** the codegen/compile plugins, pin a
version, and feed the output into the existing generator:

```xml
<!-- 1) Pull the schema from the registry into target/schemas/ -->
<plugin>
  <groupId>io.apicurio</groupId>
  <artifactId>apicurio-registry-maven-plugin</artifactId>
  <version>${apicurio-registry-maven-plugin.version}</version>
  <executions>
    <execution>
      <id>download-order-schema</id>
      <phase>generate-sources</phase>          <!-- must precede protobuf:compile -->
      <goals><goal>download</goal></goals>
      <configuration>
        <registryUrl>${apicurio.registry.url}</registryUrl>
        <!-- OIDC: read from env / CI secrets — NEVER commit these -->
        <authServerUrl>${apicurio.auth.url}</authServerUrl>
        <clientId>${apicurio.client.id}</clientId>
        <clientSecret>${apicurio.client.secret}</clientSecret>
        <artifacts>
          <artifact>
            <groupId>events.orders</groupId>
            <artifactId>OrderCreated</artifactId>
            <version>2</version>               <!-- PIN a version; do not float on latest -->
            <file>${project.build.directory}/schemas/order-created.proto</file>
          </artifact>
        </artifacts>
      </configuration>
    </execution>
  </executions>
</plugin>

<!-- 2) Point the existing generator at the downloaded file -->
<plugin>
  <groupId>org.xolstice.maven.plugins</groupId>
  <artifactId>protobuf-maven-plugin</artifactId>
  <configuration>
    <protoSourceRoot>${project.build.directory}/schemas</protoSourceRoot>
  </configuration>
  <!-- executions unchanged -->
</plugin>
```

(For `customer-contracts`, point `jsonschema2pojo`'s `<sourceDirectory>` at
`target/schemas/` the same way.) Auth values come from environment variables / CI secrets — keep
them out of the POM and out of git.

### 9.3 Recommendation

- **Stay on Model A (git-first).** It gives reproducible builds and reviewable schema changes,
  and it's what the rest of this doc (CI gating, schemas-as-code) assumes.
- **Reach for Model B only** when the registry is genuinely the canonical, cross-language source
  of truth and you accept coupling the build to a live, authenticated registry.
- **A hybrid is often best:** git-first **codegen** (Model A), with the registry as the
  **runtime + governance** source of truth (rules, version pinning, consumer schema resolution).
  That's effectively where this repo already sits.

---

## 10. Optimizing the CI & local schema-check footprint

A common worry: "compat-check and registration pull a ~700MB container — can't we just run the
plugin from the project, since everything is Java?" The first step is to locate *which* 700MB you
actually mean, because the answer is different in CI vs. locally.

### 10.1 Where the 700MB actually is

| Context | What runs Maven | The heavy container |
|---------|-----------------|---------------------|
| **CI** (`.github/workflows/schema-compat-check.yml`) | **Already native** — `actions/setup-java` + `./mvnw`. *No Maven container.* | The `apicurio/apicurio-registry:3.2.0` **service** (+ `postgres:17`), started **per matrix job** (orders *and* customers) — so the registry boots **twice** per PR. |
| **Local** (`docker-compose.yml` `schema-registrar`) | `maven:3.9-eclipse-temurin-21` (~700MB) | The Maven image itself, used only to run `register` + curl the rules. |

So the direct answer to "can we initiate the plugin from the project?": **in CI you already do**
(it's `./mvnw`, not a container); **locally you can too** (drop the Maven image — see §10.2). The
remaining CI weight is the *registry* the check needs to compare against, not Maven.

### 10.2 Local — reclaim the 700MB Maven image

The `schema-registrar` container exists only so `docker compose up` is one self-contained command.
Since you already have Java 25 + `./mvnw`, you don't need a Maven image at all.

- **L1 — drop the container, register from the host (recommended, simplest).** Delete the
  `schema-registrar` service and register with the wrapper after the registry is healthy:

  ```bash
  ./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
    -Dapicurio.registry.url=http://localhost:8080
  # then attach the BACKWARD rules (the two curl POST .../rules calls)
  ```

  Wrap those lines in `scripts/register-schemas.sh` for ergonomics. Zero extra image. The only
  cost: `docker compose up` no longer self-registers — it becomes a documented second step. (The
  compose file already lists this as the "Dev alternative.")

- **L2 — keep one-command UX with a ~10MB image.** Registration is just *POSTing schema bytes to
  the REST API* — the Maven plugin is a convenience wrapper, not a requirement. Swap
  `maven:3.9-eclipse-temurin-21` for a tiny `curlimages/curl` (~10MB) container that POSTs the
  schema files and then attaches the rules. Cost: you hand-roll the v1→v2 ordering and
  content-type that the plugin does for you.

### 10.3 CI — shrink or remove the Apicurio service (decision ladder)

Ordered from "least change, keeps the self-contained design" to "lightest, needs infra":

- **C1 — collapse the 2-job matrix into one job (biggest easy win).** Today `orders` and
  `customers` are separate matrix jobs, each spinning up its **own** Apicurio + Postgres → the
  registry boots **twice**. Run both modules in one job instead:

  ```yaml
  # one job, one registry boot — Maven's reactor still reports both modules
  run: ./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
         -Dapicurio.registry.url=http://localhost:8080 --no-transfer-progress
  ```

  Cuts heavy-container starts and the 60–90s Apicurio boot roughly in half. Trade-off: loses
  per-domain parallelism (negligible at this scale).

- **C2 — drop Postgres; use Apicurio's in-memory storage.** Since Apicurio 3.0 the **default**
  storage variant is non-persistent in-memory with no external dependencies. An ephemeral CI gate
  doesn't need durable SQL — remove the `postgres:17` service and the `APICURIO_DATASOURCE_*` /
  `APICURIO_STORAGE_SQL_KIND` env so the registry boots in-memory. One fewer container, and a
  faster boot (no DB readiness wait, no schema migration). *Verify the exact `APICURIO_STORAGE_KIND`
  value/behavior for 3.2.0 before relying on it.* (The Apicurio image is still pulled, but you've
  removed a service and sped up startup.)

- **C3 — check against a shared/persistent registry; no Apicurio container in CI at all.** Point
  the gate at a stable dev registry:

  ```yaml
  run: ./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
         -Dapicurio.registry.url=${{ secrets.DEV_REGISTRY_URL }} --no-transfer-progress
  ```

  This is also *more correct*: it compares the PR schema against what's **actually registered**,
  rather than a fresh registry the workflow seeds from the same branch (which is somewhat
  circular). Trade-off: introduces an external dependency, network, and an auth secret into PR CI;
  the current self-contained design was a deliberate choice to run on a free GitHub account with
  zero standing infrastructure. Adopt once you have a reliable shared registry.

  > **This repo has adopted C3.** The workflows run on a **self-hosted runner** sharing a machine
  > with the standing local registry, so the PR gate is a read-only dry-run against real
  > registered history (no throwaway containers, no baseline seeding). See
  > [`docs/self-hosted-runner-setup.md`](./self-hosted-runner-setup.md).

- **C4 — container-free pre-check with `buf` (Protobuf).** `buf breaking --against` the git `main`
  branch detects breaking Protobuf changes (FILE / PACKAGE / WIRE / WIRE_JSON categories) with no
  registry and no container — instant PR feedback:

  ```bash
  buf breaking --against 'https://github.com/anarefin/schema-registry-demo.git#branch=main'
  ```

  For JSON Schema, a json-schema-diff tool plays the same role. Treat this as a **fast pre-gate**,
  not a replacement: Buf's compatibility rules are not identical to Apicurio's `BACKWARD` rule, so
  keep the Apicurio check (C1–C3) or the registration-time server rule as the **authoritative**
  gate.

- Keep the existing `actions/cache@v4` on `~/.m2` — it already prevents re-downloading the plugin
  and dependencies on every run.

### 10.4 Comparison & recommendation

| Approach | Containers in CI | Boot cost | Hermetic / offline | Matches Apicurio `BACKWARD` exactly | Needs standing infra |
|----------|------------------|-----------|--------------------|--------------------------------------|----------------------|
| Current (matrix ×2, SQL) | Apicurio + Postgres, **×2** | High (×2 boots) | Yes | Yes | No |
| **C1** collapse matrix | Apicurio + Postgres, ×1 | Medium | Yes | Yes | No |
| **C1 + C2** in-memory | Apicurio only, ×1 | Low | Yes | Yes | No |
| **C3** shared registry | **None** | None | No | Yes | Yes (registry) |
| **C4** buf pre-check | None | None | Yes | **No** (different engine) | No |

**Recommendation:**
- **Quick, safe, no new infra:** apply **C1 + C2** (one job, in-memory registry) and **L1**
  locally. Keeps the design self-contained while roughly halving the heavy-container work and
  speeding startup.
- **When a shared dev registry exists:** graduate the gate to **C3** to remove the registry
  container from PR CI entirely.
- **For instant Protobuf feedback:** add **C4** (`buf breaking`) as a pre-gate, with Apicurio
  remaining the authoritative compatibility check.

---

## 11. Sources

- [Best Practices for Confluent Schema Registry](https://www.confluent.io/blog/best-practices-for-confluent-schema-registry/) — `auto.register.schemas=false` in prod, normalization, subject naming.
- [Confluent — Kafka SerDes (subject naming, normalization, `use.latest.version`)](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html)
- [Introduction to Apicurio Registry](https://www.apicur.io/registry/docs/apicurio-registry/2.5.x/getting-started/assembly-intro-to-the-registry.html) — registration options; reference-from-registry vs. package-in-app.
- [Apicurio Registry — Validating Kafka messages with SerDes](https://www.apicur.io/registry/docs/apicurio-registry/2.4.x/getting-started/assembly-using-kafka-client-serdes.html)
- [CROZ — Which Problems With Schemas Did We Have Using Apicurio Registry?](https://croz.net/problems-with-schemas-using-apicurio-registry/) — why runtime auto-register didn't scale; moving to the Maven plugin + central pipeline.
- [AutoMQ — Which Kafka Schema Registry Is Right for Your Architecture in 2026?](https://www.automq.com/blog/kafka-schema-registry-confluent-aws-glue-redpanda-apicurio-2025) — Apicurio vs. Confluent/Glue/Redpanda, wire compatibility.
- [Apicurio Registry User Guide — authentication & RBAC (OIDC, `sr-admin`/`sr-developer`/`sr-readonly`)](https://www.apicur.io/registry/docs/apicurio-registry/2.5.x/getting-started/assembly-intro-to-the-registry.html) — UI/API auth model and roles (§8).
- [Apicurio Registry Maven plugin — `register` / `test` / `download` goals](https://github.com/Apicurio/apicurio-registry) — the build-time integration surface, including the `download` goal used in the Model B recipe (§9).
- [Configuring Apicurio Registry storage (3.x)](https://www.apicur.io/registry/docs/apicurio-registry/3.0.x/getting-started/assembly-installing-registry-docker.html) — single artifact, `APICURIO_STORAGE_KIND`; in-memory is the default no-dependency variant (§10.3 C2).
- [Buf — Detecting breaking changes](https://buf.build/docs/breaking/) — `buf breaking --against` a git branch; FILE/PACKAGE/WIRE/WIRE_JSON rule categories, no registry needed (§10.3 C4).
