# End-to-End Testing Guide: payment-contracts Code-First Workflow

Covers every verifiable outcome from `spec/tasks.md` — from a clean clone through the
compatibility gate demo. Run sections 1–3 entirely offline. Section 4 requires Docker.

---

## Prerequisites

| Requirement | Check |
|---|---|
| JDK 25 toolchain | `~/.m2/toolchains.xml` has a `<jdk>25</jdk>` entry — the build uses `maven-toolchains-plugin` |
| Maven Wrapper | Always use `./mvnw`, never a system `mvn` |
| Docker Desktop | Running and responsive — required for Section 4 only |

**Toolchain entry format** (reference):
```xml
<toolchain>
  <type>jdk</type>
  <provides><version>25</version></provides>
  <configuration><jdkHome>/path/to/jdk-25</jdkHome></configuration>
</toolchain>
```

---

## Section 1 — Module scaffold & compile (T-1.x, T-2.x)

### Step 1 — Module resolves and validates

```bash
./mvnw -pl payment-contracts validate
```

**Expected:** `BUILD SUCCESS`

**What this proves:** Parent POM resolution works; `payment-contracts` is declared as a module;
victools 4.38.0, jakarta.validation-api 3.1.1, and exec-maven-plugin 3.5.0 are all version-managed
from the parent — no version re-declared in the module POM.

---

### Step 2 — Event model and generator compile

```bash
./mvnw -pl payment-contracts compile
```

**Expected:** `BUILD SUCCESS` — zero compilation errors.

**What this proves:** `PaymentProcessedEvent` (6-field Java record) and `SchemaGenerator` both
resolve cleanly against victools + jakarta.validation-api. Annotations
(`@NotNull`, `@DecimalMin`, `@Size`, `@Pattern`) are on the compile classpath.

---

## Section 2 — Schema generation & enrichment (T-3.x, T-4.1, T-4.2)

### Step 3 — Generate the JSON Schema

```bash
./mvnw -pl payment-contracts process-classes
```

**Expected:** `BUILD SUCCESS`; `exec-maven-plugin` invokes `SchemaGenerator.main()` and writes
(or overwrites) `payment-contracts/src/main/resources/schemas/payment-processed.schema.json`.

**What this proves:** The `exec-maven-plugin` execution is bound to the `process-classes` phase
and wired to the correct main class and output path.

---

### Step 4 — Inspect schema enrichment

Open the generated file and verify each annotation translated correctly:

```json
{
  "$id" : "PaymentProcessedEvent",
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "properties" : {
    "amount" : {
      "exclusiveMinimum" : 0.01,
      "type" : "number"
    },
    "currency" : {
      "maxLength" : 3,
      "minLength" : 3,
      "type" : "string"
    },
    "orderId"    : { "format" : "uuid", "type" : "string" },
    "paymentId"  : { "format" : "uuid", "type" : "string" },
    "paymentMethod" : {
      "pattern" : "^(CARD|BANK_TRANSFER|WALLET)$",
      "type" : "string"
    },
    "processedAt" : { "format" : "date-time", "type" : "string" }
  },
  "required" : [ "amount", "currency", "orderId", "paymentId" ],
  "title" : "PaymentProcessedEvent",
  "type" : "object"
}
```

**Checklist — every item must be true:**

| Annotation on record | Expected schema constraint | Field |
|---|---|---|
| `@DecimalMin(value="0.01", inclusive=false)` | `"exclusiveMinimum": 0.01` | `amount` |
| `@Pattern(regexp="^(CARD\|BANK_TRANSFER\|WALLET)$")` | `"pattern": "^(CARD\|BANK_TRANSFER\|WALLET)$"` | `paymentMethod` |
| `@Size(min=3, max=3)` | `"minLength": 3, "maxLength": 3` | `currency` |
| `@NotNull` (4 fields) | `"required": ["amount","currency","orderId","paymentId"]` | — |
| No `@NotNull` | `paymentMethod` and `processedAt` absent from `required` | — |
| Determinism config | All object keys alphabetically sorted at every level | — |
| Schema dialect | `"$schema": "https://json-schema.org/draft/2020-12/schema"` | — |

**What this proves:** victools `JakartaValidationModule` with
`NOT_NULLABLE_FIELD_IS_REQUIRED` + `INCLUDE_PATTERN_EXPRESSIONS` correctly translates every
Jakarta Validation annotation into its JSON Schema equivalent. `exclusiveMinimum` (not `minimum`)
confirms `inclusive=false` is honoured.

---

## Section 3 — Determinism & drift gate (T-4.3, T-6.1)

### Step 5 — Run the determinism test suite

```bash
./mvnw -pl payment-contracts test
```

**Expected:** `BUILD SUCCESS`; Surefire runs `SchemaDeterminismTest` — both test methods green.

**What this proves (two assertions inside the test):**

1. `generatingTwiceProducesByteIdenticalOutput` — calling `SchemaGenerator.generateSchema()`
   twice in the same JVM produces byte-identical strings. Guards against any non-determinism
   in victools/Jackson ordering.

2. `committedFileMatchesFreshGeneration` — the committed
   `src/main/resources/schemas/payment-processed.schema.json` matches a freshly generated schema
   byte-for-byte. This is the local mirror of the CI drift gate: it fails if the committed
   artifact is stale.

---

### Step 6 — Double-build leaves the working tree clean

```bash
./mvnw -pl payment-contracts process-classes
git status
```

**Expected:** `git status` shows **no changes** under
`payment-contracts/src/main/resources/schemas/`.

**What this proves:** Idempotent generation — running `process-classes` a second time does not
produce a different schema file, so CI never sees phantom diffs after a clean checkout.

---

### Step 7 — Full offline install

```bash
./mvnw -pl payment-contracts clean install
```

**Expected:** `BUILD SUCCESS` — compile → generate → test all pass in sequence.

**What this proves:** The complete module lifecycle works end-to-end with a single command,
matching the primary acceptance criterion from tasks.md §End-to-end verification item 1.

---

### Step 8 — Drift detection demo (simulates the CI gate locally)

This demo shows what happens when a POJO annotation changes but the schema artifact is not
regenerated and committed. The CI `schema-drift-check.yml` catches exactly this scenario.

**8a — Edit the record:** Open
`payment-contracts/src/main/java/com/example/contracts/payments/codefirst/PaymentProcessedEvent.java`
and add `@NotNull` to `paymentMethod`:

```java
// Before:
@Pattern(regexp = "^(CARD|BANK_TRANSFER|WALLET)$") String paymentMethod,

// After:
@NotNull @Pattern(regexp = "^(CARD|BANK_TRANSFER|WALLET)$") String paymentMethod,
```

**8b — Regenerate and see the diff:**

```bash
./mvnw -pl payment-contracts process-classes
git diff -- payment-contracts/src/main/resources/schemas/
```

**Expected:** The diff shows `"paymentMethod"` added to the `"required"` array. The schema
changed because the annotation changed.

**8c — Revert the schema only (keep the Java edit in place):**

```bash
git checkout -- payment-contracts/src/main/resources/schemas/payment-processed.schema.json
```

**8d — Simulate the CI drift check:**

```bash
./mvnw -pl payment-contracts process-classes && \
  git diff --exit-code -- payment-contracts/src/main/resources/schemas/
```

**Expected:** The compound command exits non-zero; `git diff` prints the schema diff and the
shell reports a non-zero exit. This is exactly the failure mode that `schema-drift-check.yml`
triggers on a PR.

**What this proves:** A developer who edits the record and forgets to regenerate + commit the
schema will be caught at CI before merge.

**8e — Restore clean state:**

```bash
git checkout -- payment-contracts/src/main/java/com/example/contracts/payments/codefirst/PaymentProcessedEvent.java
./mvnw -pl payment-contracts process-classes
git status   # must be clean
```

---

## Section 4 — Compatibility gate (T-5.x, T-6.2)

Requires a running Apicurio registry. The compat gate is **read-only** (`dryRun=true`): it
validates the candidate schema against the registered history without writing anything.

### Step 9 — Start infrastructure

```bash
docker compose up -d
```

Wait for the registry to be ready:

```bash
curl -s http://localhost:8080/apis/registry/v3/system/info | grep version
```

**Expected:** JSON response containing the Apicurio version (3.2.0).

---

### Step 10 — Bootstrap all artifacts and attach FORWARD rules

Register all three contract schemas:

```bash
./mvnw -pl order-contracts,customer-contracts,payment-contracts \
  apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
```

**Expected:** `BUILD SUCCESS`; three artifacts registered (or found as existing versions).

Attach a FORWARD compatibility rule to `events.payments/PaymentProcessed` (only needs to run
once — skip if the rule is already attached):

```bash
curl -s -X POST \
  http://localhost:8080/apis/registry/v3/groups/events.payments/artifacts/PaymentProcessed/rules \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
```

**Expected:** HTTP 204 (created) or a "rule already exists" response — either is fine.

**What this proves:** The bootstrap path from tasks.md T-5.2 is wired; the gate is now meaningful
for subsequent PRs.

---

### Step 11 — Compat gate PASSES for an optional-field addition

An optional field (no `@NotNull`) adds a property to the schema without making it required —
FORWARD-compatible under JSON Schema rules.

**11a — Add an optional field to the record:**

```java
// Add at the end of PaymentProcessedEvent components:
String notes,   // no @NotNull — optional in schema
```

**11b — Regenerate:**

```bash
./mvnw -pl payment-contracts process-classes
```

**11c — Run the compat gate:**

```bash
./mvnw -pl payment-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected:** `BUILD SUCCESS` — Apicurio accepts the candidate schema; adding an optional
property does not break FORWARD compatibility.

**What this proves:** The `compat-check` Maven profile binds the `apicurio-registry:register
dryRun=true` goal to the `verify` phase and correctly passes validation for safe changes.

**11d — Revert:**

```bash
git checkout -- payment-contracts/src/main/java/
./mvnw -pl payment-contracts process-classes
git status   # must be clean
```

---

### Step 12 — Compat gate FAILS for a required-field addition

Adding a `@NotNull` field makes it appear in `"required"` — this narrows the schema and breaks
FORWARD compatibility (Apicurio classifies it as `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`).

**12a — Add a required field to the record:**

```java
// Add at the end of PaymentProcessedEvent components:
@NotNull String merchantId,   // @NotNull — required in schema
```

**12b — Regenerate:**

```bash
./mvnw -pl payment-contracts process-classes
```

**12c — Run the compat gate:**

```bash
./mvnw -pl payment-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected:** `BUILD FAILURE` — Apicurio rejects the candidate schema with a compatibility
violation. The Maven build prints the rejection reason from the registry.

**What this proves:** The gate blocks breaking changes at the PR stage before they reach `main`.

**12d — Restore clean state:**

```bash
git checkout -- payment-contracts/src/main/java/
./mvnw -pl payment-contracts process-classes
git status   # must be clean
```

---

## Section 5 — CI workflow verification (T-6.x)

### Step 13 — Drift check workflow (offline, T-6.1)

Verify `.github/workflows/schema-drift-check.yml` contains the expected structure:

| Element | Expected value |
|---|---|
| `on.pull_request.paths` | `['payment-contracts/**']` |
| `runs-on` | `ubuntu-latest` |
| Java distribution / version | temurin / 25 |
| Regeneration step | `./mvnw -pl payment-contracts process-classes --no-transfer-progress` |
| Drift check step | `git diff --exit-code -- payment-contracts/src/main/resources/schemas/` |

**What this proves:** Any PR that modifies `payment-contracts/` will trigger an offline schema
re-generation and fail if the committed artifact is stale — no registry dependency.

---

### Step 14 — Compat check workflow (online, T-6.2)

Verify `.github/workflows/schema-compat-check.yml` covers `payment-contracts`:

| Element | Expected value |
|---|---|
| `on.pull_request.paths` | includes `payment-contracts/**` |
| `runs-on` | `[self-hosted, apicurio-local]` |
| `-pl` argument | `order-contracts,customer-contracts,payment-contracts` |
| Profile flag | `-Pcompat-check` |
| Registry URL env var | `REGISTRY_URL: http://localhost:8080` |

**What this proves:** The `payment-contracts` compat gate is wired into the same CI job as the
existing schema-first modules and will run on every PR touching payment contracts code.

---

## Quick reference: command summary

| Goal | Command |
|---|---|
| Validate module | `./mvnw -pl payment-contracts validate` |
| Compile only | `./mvnw -pl payment-contracts compile` |
| Generate schema | `./mvnw -pl payment-contracts process-classes` |
| Run determinism tests | `./mvnw -pl payment-contracts test` |
| Full offline build | `./mvnw -pl payment-contracts clean install` |
| Local drift simulation | `./mvnw -pl payment-contracts process-classes && git diff --exit-code -- payment-contracts/src/main/resources/schemas/` |
| Bootstrap registry | `./mvnw -pl order-contracts,customer-contracts,payment-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080` |
| Compat gate check | `./mvnw -pl payment-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080` |
