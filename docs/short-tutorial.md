# Apicurio Schema Registry — Beginner Tutorial

This tutorial explains two things:

1. **How Apicurio Schema Registry's compatibility modes work** — with real examples from this repo
2. **How the three GitHub Actions workflows work** — what triggers them, what they do, and how they fit together

No prior knowledge of schema registries required. Every command shown is runnable against this repo.

---

## Table of Contents

1. [What is a Schema Registry?](#1-what-is-a-schema-registry)
2. [Compatibility Modes](#2-compatibility-modes)
   - [BACKWARD — the Protobuf story](#21-backward--the-protobuf-story)
   - [FORWARD — the JSON Schema story](#22-forward--the-json-schema-story)
   - [All modes at a glance](#23-all-modes-at-a-glance)
3. [The Three GitHub Workflows](#3-the-three-github-workflows)
   - [Workflow 1: Bootstrap (run once)](#workflow-1-schema-governance-bootstrapyml)
   - [Workflow 2: Compatibility Check (every PR)](#workflow-2-schema-compat-checkyml)
   - [Workflow 3: Registration (post-merge)](#workflow-3-schema-registeryml)
   - [How they connect](#how-they-connect)
4. [Hands-On Examples](#4-hands-on-examples)
5. [Cheat Sheet](#5-cheat-sheet)

---

## 1. What is a Schema Registry?

### The problem

Imagine a **producer service** that publishes `OrderCreated` messages to RabbitMQ, and a **consumer service** that reads them. Both agree on the message format today. But what happens when the producer team adds a new field, renames one, or changes a field's type?

- If there is no shared contract, the consumer silently misreads the message — or crashes.
- If teams coordinate only by Slack/docs, mistakes slip through.

### The solution

A **schema registry** is a central service that stores the official, versioned definition of each message type. Before a producer can send a message, the schema must exist in the registry. Before a consumer reads one, it fetches the schema to validate and deserialize correctly.

**Apicurio Registry** is the open-source registry used in this project. It stores schemas under a two-part address:

```
group / artifact-id
```

This project has two artifacts:

| Group | Artifact ID | Schema type |
|-------|-------------|-------------|
| `events.orders` | `OrderCreated` | Protobuf |
| `events.customers` | `CustomerRegistered` | JSON Schema |

### Key terms

| Term | Meaning |
|------|---------|
| **Artifact** | A named schema (e.g., `OrderCreated`) |
| **Version** | One immutable snapshot of an artifact (v1, v2, …) |
| **Compatibility rule** | A policy attached to an artifact: what changes are allowed? |
| **Group** | A namespace for organizing artifacts (like a folder) |

---

## 2. Compatibility Modes

A compatibility rule tells Apicurio: *"When someone tries to register a new version, check it against the existing version(s). Reject it if it breaks this rule."*

This project uses two different rules for its two artifacts, for good reasons explained below.

---

### 2.1 BACKWARD — the Protobuf story

**Artifact:** `events.orders / OrderCreated` (Protobuf)

**Rule:** `BACKWARD`

**What BACKWARD means:** A consumer built against the *new* schema must still be able to read messages written with the *old* schema. In other words: **new code, old data — must work**.

#### Why Protobuf fits BACKWARD

Protobuf serializes fields by number (field tag), not by name. When a consumer sees a field number it does not recognize, it silently skips it. This means:

- Adding a new optional field → old messages simply omit it → new consumer reads it as the zero-value → safe
- Removing a field → old messages may include it → new consumer ignores the bytes → safe (with caveats)
- **Changing a field's type on the same number** → wire format mismatch → new consumer misreads bytes → **BACKWARD-incompatible**

#### Schema evolution in this repo

**v1 — original** (`order-created-v1.proto`, fields 1–7):

```protobuf
message OrderCreated {
  string order_id    = 1;
  string customer_id = 2;
  string product_id  = 3;
  int32  quantity    = 4;
  double total_amount = 5;
  string currency    = 6;
  string created_at  = 7;
}
```

**v2 — current** (`order-created.proto`, adds fields 8–10):

```protobuf
message OrderCreated {
  string order_id    = 1;
  // ... fields 2–7 unchanged ...
  optional string promo_code = 8;   // added in v2 — BACKWARD-compatible
  optional string notes      = 9;   // added in v2
  optional string input1     = 10;  // added later
}
```

Fields 8–10 are new. A consumer compiled against v1 (fields 1–7 only) receives a v2 message and simply ignores fields 8–10. The BACKWARD rule in Apicurio approves this registration.

**What a BACKWARD-incompatible change looks like** (`order-created-incompatible.proto`):

```protobuf
message OrderCreated {
  int64 order_id = 1;   // WAS string — same field number, different wire type!
  ...
}
```

Field 1 changes from `string` (wire type 2, length-delimited) to `int64` (wire type 0, varint). An old consumer reading this message with the old schema will try to decode varint bytes as a length-delimited string → garbled data. The BACKWARD rule rejects this.

> **Rule of thumb for Protobuf + BACKWARD:** You can add optional fields with new numbers. You can never change the type or reuse a number.

---

### 2.2 FORWARD — the JSON Schema story

**Artifact:** `events.customers / CustomerRegistered` (JSON Schema)

**Rule:** `FORWARD`

**What FORWARD means:** A consumer built against the *old* schema must still be able to read messages written with the *new* schema. In other words: **old code, new data — must work**.

#### Why JSON Schema needs FORWARD (not BACKWARD)

This is a subtle but important point. In Apicurio's JSON Schema compatibility checker, adding *any* property to a schema — even an optional one — is classified as `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`. The logic: the new schema is "narrower" (more restrictive) because it now defines more constraints.

This means:
- BACKWARD rejects adding optional properties (even though it would be safe at runtime)
- FORWARD accepts adding optional properties (old consumers using Jackson ignore unknown fields by default)

So to allow optional field additions — the most common evolution — you must use `FORWARD`.

#### Schema evolution in this repo

**v1 — original** (`customer-registered-v1.json`, 4 required + 2 optional):

```json
{
  "properties": {
    "customerId": { "type": "string" },
    "email":      { "type": "string" },
    "firstName":  { "type": "string" },
    "lastName":   { "type": "string" },
    "phoneNumber":  { "type": "string" },
    "registeredAt": { "type": "string" }
  },
  "required": ["customerId", "email", "firstName", "lastName"]
}
```

**v3 — current** (`customer-registered.json`, adds `promoCode` and `input1`):

```json
{
  "properties": {
    "customerId":   { "type": "string" },
    "email":        { "type": "string" },
    "firstName":    { "type": "string" },
    "lastName":     { "type": "string" },
    "phoneNumber":  { "type": "string" },
    "registeredAt": { "type": "string" },
    "promoCode":    { "type": "string" },   // added in v2 — FORWARD-compatible
    "input1":       { "type": "string" }    // added in v3 — FORWARD-compatible
  },
  "required": ["customerId", "email", "firstName", "lastName"]
}
```

`promoCode` and `input1` are new optional properties. A consumer compiled against v1 reads a v3 JSON payload; Jackson sees the extra fields and ignores them (default behavior). The FORWARD rule in Apicurio approves this registration.

**What a FORWARD-incompatible change looks like** (`customer-registered-incompatible.json`):

```json
{
  "properties": {
    ...same as v3...
    "accountType": { "type": "string" }
  },
  "required": ["customerId", "email", "firstName", "lastName", "accountType"]
}
```

`accountType` is added to the `required` array. An old consumer reads a new message and tries to validate it with the v1 schema. The v1 schema does not know about `accountType`, but the new message requires it — old code cannot satisfy the new requirement. The FORWARD rule rejects this.

> **Rule of thumb for JSON Schema + FORWARD:** You can add new optional properties (not in `required`). You can never add a new required field, remove an existing required field, or change a field's type.

---

### 2.3 All modes at a glance

| Mode | What Apicurio checks | Typical use case |
|------|---------------------|------------------|
| `BACKWARD` | New schema can read data written by old schema | Protobuf, Avro — consumers are upgraded first |
| `FORWARD` | Old schema can read data written by new schema | JSON Schema — consumers may lag behind producers |
| `FULL` | Both BACKWARD and FORWARD must hold | High-confidence environments; more restrictive |
| `NONE` | No compatibility checking | Development / prototyping only — risky in production |
| `BACKWARD_TRANSITIVE` | New schema must be BACKWARD-compatible with *every* prior version, not just the latest | Long-lived schemas with many consumers on older versions |
| `FORWARD_TRANSITIVE` | Old schemas *all the way back* must read new data | Same as above, FORWARD direction |
| `FULL_TRANSITIVE` | Both directions, all versions | Maximum safety |

**This project uses:**
- `BACKWARD` for `OrderCreated` (Protobuf)
- `FORWARD` for `CustomerRegistered` (JSON Schema)

The transitive variants check against all registered history, not just the immediately prior version. They are stricter and slower (more versions to validate), but protect consumers that have not upgraded in a long time.

---

## 3. The Three GitHub Workflows

These three workflows form a complete schema governance pipeline. Each has a distinct role.

```
┌──────────────────────────────────────────────────────────────┐
│                  Schema Governance Pipeline                   │
│                                                              │
│  1. Bootstrap (manual, once)                                 │
│     Attach BACKWARD/FORWARD rules to artifacts               │
│            │                                                 │
│            ▼                                                 │
│  2. Compat-Check (automatic, every PR)                       │
│     Dry-run: validate PR schema — read-only, no writes       │
│            │ PASS                                            │
│            ▼                                                 │
│  3. Register (automatic, post-merge)                         │
│     Write: register new schema version to Apicurio           │
└──────────────────────────────────────────────────────────────┘
```

All three workflows run on a **self-hosted GitHub Actions runner** that shares the same machine as the Apicurio registry (running via `docker compose up`). The registry is reachable at `http://localhost:8080`.

---

### Workflow 1: `schema-governance-bootstrap.yml`

**File:** `.github/workflows/schema-governance-bootstrap.yml`

**Trigger:** Manual — you go to *Actions → Schema Governance Bootstrap → Run workflow* in the GitHub UI.

**When to run:** Once per environment, after the first `docker compose up` and before any CI automation. You can safely re-run it — if a rule already exists, Apicurio returns HTTP 409, which the workflow treats as success.

**What it does:** Attaches compatibility rules to both artifacts by calling the Apicurio REST API:

```yaml
steps:
  - name: Resolve registry URL        # defaults to http://localhost:8080
  - name: Attach rule — OrderCreated  # POST BACKWARD rule
  - name: Attach rule — CustomerRegistered  # POST FORWARD rule
  - name: Verify rules are active     # GET rules to confirm
```

The actual curl calls look like this (from the workflow):

```bash
# Attach BACKWARD to OrderCreated
curl -X POST "http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'

# Attach FORWARD to CustomerRegistered
curl -X POST "http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
```

**Key point:** Without this step, neither of the other workflows has any rules to enforce. The schema can change in any way and Apicurio will accept it silently.

**Optional input:** You can override the registry URL at run time (useful if you point at a staging registry instead of localhost).

---

### Workflow 2: `schema-compat-check.yml`

**File:** `.github/workflows/schema-compat-check.yml`

**Trigger:** Automatically on any pull request that modifies files under `order-contracts/` or `customer-contracts/`.

**What it does:** Runs a **read-only dry-run** compatibility check — it contacts the live registry to validate the PR's schema against the registered history, but writes nothing.

```yaml
on:
  pull_request:
    paths:
      - 'order-contracts/**'
      - 'customer-contracts/**'

steps:
  - name: Checkout
  - name: Set up Java 21
  - name: Run compatibility check (dry-run, no writes)
    run: |
      ./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
        -Dapicurio.registry.url=http://localhost:8080
```

The `-Pcompat-check` Maven profile activates the `apicurio-registry-maven-plugin` with `dryRun=true`. This tells the plugin: "Pretend to register this schema, apply the compatibility rules, and return pass/fail — but do not create a new version in the registry."

**If the check passes:** The workflow completes with `BUILD SUCCESS`. The PR can proceed to merge review.

**If the check fails:** The workflow completes with `BUILD FAILURE` and a `RuleViolationException` in the log. The PR is blocked until the incompatible change is fixed.

**Example failure output:**

```
[ERROR] PUT .../artifacts/OrderCreated/versions?dryRun=true → HTTP 409
[ERROR] RuleViolationException: Incompatible schema change detected.
[ERROR] INCOMPATIBLE_SCHEMA: Incompatible change detected
[INFO] BUILD FAILURE
```

**Why dry-run?** Registration is a one-way operation — a version once registered cannot be deleted (in a governed registry). The compatibility check gate needs to be safe to run on every PR without polluting the registry with rejected or in-progress versions.

---

### Workflow 3: `schema-register.yml`

**File:** `.github/workflows/schema-register.yml`

**Trigger:** Automatically when a commit lands on the `main` branch and it modifies files under `order-contracts/` or `customer-contracts/`.

**What it does:** Registers the new (already-validated) schema version into Apicurio. This is the actual write path.

```yaml
on:
  push:
    branches: [main]
    paths:
      - 'order-contracts/**'
      - 'customer-contracts/**'

steps:
  - name: Checkout
  - name: Set up Java 21
  - name: Register schemas
    run: |
      ./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
        -Dapicurio.registry.url=http://localhost:8080
```

**`FIND_OR_CREATE_VERSION`:** The Maven plugin is configured with `ifExists=FIND_OR_CREATE_VERSION`. This means: if this exact schema content is already registered (e.g., the workflow runs twice due to a re-push), Apicurio finds the existing version rather than creating a duplicate. The operation is **idempotent** — safe to run multiple times.

**What gets registered:** Both `order-contracts` and `customer-contracts` are registered every time (even if only one changed). This keeps the workflow simple and the configuration consistent.

**Key point:** This workflow only runs on `main`. It cannot run on a feature branch or PR. By the time it runs, Workflow 2 has already confirmed compatibility — so the registration should always succeed.

---

### How they connect

Here is the complete lifecycle of a schema change:

```
Developer edits order-created.proto
      │
      ▼
Opens a PR
      │
      ▼
[Workflow 2: schema-compat-check.yml]
Dry-run against live registry
      │
      ├─── FAIL ──► PR blocked; developer fixes the change
      │
      └─── PASS ──► PR approved and merged to main
                          │
                          ▼
              [Workflow 3: schema-register.yml]
              Register new version to Apicurio
                          │
                          ▼
              New version is live in registry
              Consumers can fetch and use it
```

Workflow 1 (`bootstrap`) is a prerequisite that runs once before this cycle starts, attaching the rules that Workflows 2 and 3 rely on.

---

## 4. Hands-On Examples

These four examples correspond to the four real schema files in the repo. Run them against a live Apicurio instance (`docker compose up`).

> **Prerequisite:** Registry running at `http://localhost:8080`, schemas registered, and Bootstrap workflow already run (or rules attached manually). See the README §2 for the startup sequence.

---

### EX-1 — Compatible Protobuf change (PASS)

**What:** Add a new optional field to `order-created.proto`.

**Try it yourself:** Open `order-contracts/src/main/resources/schemas/order-created.proto` and add field 11:

```protobuf
optional string delivery_note = 11;
```

**Run the compat check:**

```bash
./mvnw -pl order-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected result:**

```
[INFO] BUILD SUCCESS
```

The BACKWARD rule approves it: old consumers ignore field 11, new consumers read it as empty string if absent.

**Undo:** Remove the field you added before committing (or keep it if you want to evolve the schema for real).

---

### EX-2 — Incompatible Protobuf change (FAIL)

**What:** The `order-created-incompatible.proto` test file changes `order_id` from `string` to `int64` — same field number (1), different wire type.

**Run the incompatible demo:**

```bash
./mvnw -pl order-contracts verify -Pincompatible-demo \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected result:**

```
[ERROR] RuleViolationException: Incompatible schema change detected.
[INFO] BUILD FAILURE
```

The BACKWARD rule rejects it: a consumer built against the old schema (field 1 = string) would receive varint bytes and produce garbage.

> This uses `dryRun=true` — nothing is written to the registry. You can run it repeatedly.

---

### EX-3 — Compatible JSON Schema change (PASS)

**What:** Add an optional property to `customer-registered.json`.

**Try it yourself:** Open `customer-contracts/src/main/resources/schemas/customer-registered.json` and add a new optional property (do NOT add it to `required`):

```json
"referralSource": {
  "type": "string",
  "description": "How the customer heard about us"
}
```

**Run the compat check:**

```bash
./mvnw -pl customer-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected result:**

```
[INFO] BUILD SUCCESS
```

The FORWARD rule approves it: old consumers (Jackson) ignore the unknown `referralSource` field.

---

### EX-4 — Incompatible JSON Schema change (FAIL)

**What:** The `customer-registered-incompatible.json` test file adds `accountType` to the `required` array.

**Run the incompatible demo:**

```bash
./mvnw -pl customer-contracts verify -Pincompatible-demo \
  -Dapicurio.registry.url=http://localhost:8080
```

**Expected result:**

```
[ERROR] RuleViolationException: OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED
[INFO] BUILD FAILURE
```

The FORWARD rule rejects it: an old consumer sees a new message with `accountType` required, but its schema does not know about this field — old code cannot satisfy the constraint.

> Also uses `dryRun=true`. Safe to run repeatedly.

---

## 5. Cheat Sheet

### Compatibility rules — quick reference

| If you want to... | Use rule | Allowed changes |
|-------------------|----------|----------------|
| Let new consumers read old messages | `BACKWARD` | Add optional fields; do not change types or reuse field numbers |
| Let old consumers read new messages | `FORWARD` | Add non-required properties; do not add required fields |
| Both directions | `FULL` | Intersection of BACKWARD and FORWARD |
| Check all versions, not just latest | Add `_TRANSITIVE` suffix | Same as above but stricter |

### Maven commands

```bash
# Check compatibility (dry-run, no writes) — run on every PR
./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080

# Register schemas (writes to registry) — run post-merge
./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
  -Dapicurio.registry.url=http://localhost:8080

# Run incompatible demos to see rejection in action
./mvnw -pl order-contracts verify -Pincompatible-demo \
  -Dapicurio.registry.url=http://localhost:8080

./mvnw -pl customer-contracts verify -Pincompatible-demo \
  -Dapicurio.registry.url=http://localhost:8080
```

### REST API — attach rules manually

```bash
# Attach BACKWARD to OrderCreated
curl -X POST "http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'

# Attach FORWARD to CustomerRegistered
curl -X POST "http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'

# Check what rules are active
curl http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules
curl http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules
```

### GitHub workflow triggers

| Workflow | Trigger | Action |
|----------|---------|--------|
| `schema-governance-bootstrap.yml` | Manual (run once) | Attach compatibility rules via REST |
| `schema-compat-check.yml` | PR touching `*-contracts/` | Dry-run validate — blocks merge on failure |
| `schema-register.yml` | Push to `main` touching `*-contracts/` | Register new schema version |

---

## Further Reading

- `docs/CI-SCHEMA-TESTING-GUIDE.md` — detailed decision matrix and worked examples for every change type
- `docs/apicurio-best-practices.md` — production patterns: CI/CD-only registration, version pinning, server-side rules
- `docs/self-hosted-runner-setup.md` — how the self-hosted runner is configured
- Apicurio Registry docs: [apicurio.io/docs/registry](https://www.apicur.io/registry/docs/)
