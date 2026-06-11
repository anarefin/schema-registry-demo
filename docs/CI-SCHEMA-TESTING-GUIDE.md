# CI Schema-Governance Testing Guide

> **Refactor note (June 2026):** this POC has been refactored to be **JSON Schema-only**.
> `OrderCreated` was converted from Protobuf to a JSON Schema artifact (generated POJO via
> jsonschema2pojo) and now carries a **FORWARD** compatibility rule, same as
> `CustomerRegistered`. Protobuf/BACKWARD passages below predate the refactor — treat them as
> historical/educational context; the schema files are now `order-created*.json` and the wire
> format is always `application/json`.

A **reusable, dynamic playbook** for testing how a schema change flows through GitHub Actions
schema governance — for both **Protobuf** (`order-contracts`) and **JSON Schema**
(`customer-contracts`). It covers the **success** path (a compatible change is accepted) and the
**failure** path (an incompatible change is rejected at the gate).

> **Note on rules:** the two artifacts use different compatibility levels — `OrderCreated`
> (Protobuf) is **BACKWARD**, `CustomerRegistered` (JSON Schema) is **FORWARD**. Apicurio's JSON
> Schema checker classifies adding any property (even optional) as a "narrowing" that BACKWARD
> rejects, so JSON field additions are only valid under FORWARD.

> **Companion doc:** For the full local end-to-end (infra → unit/IT tests → manual happy path →
> DLQ → observability) see [`TESTING-GUIDE.md`](./TESTING-GUIDE.md). This guide is **CI +
> schema-evolution only** and does not repeat that material.

---

## 1. Purpose & how to use this doc

This doc is run **repeatedly**, by a human and by an agent. Every scenario follows the same
loop:

```
edit a field  →  run the compat gate  →  observe PASS/FAIL  →  clean up
```

Two ways to "run the gate" are documented **side by side** for every scenario:

- **Local equivalent** — run the same Maven goal CI runs, directly on your machine (needs a
  registry at `:8080`). Fast inner loop.
- **Real CI** — open a PR / push to `main` and read the GitHub Actions run on the
  self-hosted runner. Faithful to production governance.

Each scenario is tagged so you know whether to back it out:

- 🟢 **GENUINE** — a real schema evolution you intend to keep (commit + register).
- 🟡 **THROWAWAY** — a demo of a rejection; revert it afterwards with `git restore`.

**Agents:** use the [parameterized playbook](#5-the-reusable-playbook-dynamic-template). Fill in
`<MODULE>`, `<SCHEMA_FILE>`, `<FIELD>` and follow the steps. Pick the expected outcome from the
[decision matrix](#4-decision-matrix-change-type--outcome) before running, then confirm reality
matches.

---

## 2. Prerequisites

1. **Standing registry up & seeded.** From the repo root:
   ```bash
   docker compose up -d
   ```
   Wait for all services healthy, then register both artifacts (v1 + v2) from the host and attach
   their compatibility rules (BACKWARD for orders, FORWARD for customers) — see step 4 below
   (the contracts modules carry the
   `apicurio-registry-maven-plugin`, so no separate container is needed). Registry API:
   `http://localhost:8080`, UI: `http://localhost:8888`.

2. **Java 25 toolchain.** A JDK-25 entry must exist in `~/.m2/toolchains.xml` (the Maven runtime
   may be Java 21; the compile toolchain is Java 25). See `CLAUDE.md` → *Build conventions*.

3. **(Real-CI runs only) self-hosted runner online.** GitHub Actions here run on a runner
   labelled `[self-hosted, apicurio-local]` that shares the host with the registry above. The
   workflows hardcode `REGISTRY_URL=http://localhost:8080` (the runner's localhost) — there is
   **no** GitHub-hosted fallback.

4. **(One-time per fresh registry) Schemas registered + compatibility rules attached.** After the
   registry is healthy, register both artifacts from the host and attach their rules (`OrderCreated`
   → BACKWARD, `CustomerRegistered` → FORWARD):
   ```bash
   ./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
          -Dapicurio.registry.url=http://localhost:8080
   curl -X POST http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules \
     -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'
   curl -X POST http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules \
     -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
   ```
   The rule attachment can also be run via the **Schema Governance Bootstrap** workflow
   (Actions → *Schema Governance Bootstrap* → *Run workflow*). HTTP `409` means the rule already
   exists — that's fine.

---

## 3. How CI schema governance works

Three workflows under `.github/workflows/`, all on `runs-on: [self-hosted, apicurio-local]`,
`REGISTRY_URL: http://localhost:8080`, Java 21 runtime + Java 25 via `~/.m2/toolchains.xml`:

| Workflow | File | Trigger | What it runs | Semantics |
|---|---|---|---|---|
| **Compatibility Check** | `schema-compat-check.yml` | **PR** touching `order-contracts/**` or `customer-contracts/**` | `./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check -Dapicurio.registry.url=$REGISTRY_URL` | **Read-only dry-run** (`dryRun=true`). Validates the PR schema against the registry's real history. Fails the check (blocks merge) on `INCOMPATIBLE`. |
| **Schema Registration** | `schema-register.yml` | **push to `main`** touching the same paths | `./mvnw -pl order-contracts,customer-contracts apicurio-registry:register -Dapicurio.registry.url=$REGISTRY_URL` | **Write path.** Idempotent (`FIND_OR_CREATE_VERSION`) — adds a new version only if the schema bytes changed. |
| **Governance Bootstrap** | `schema-governance-bootstrap.yml` | **`workflow_dispatch`** (manual) | POSTs `BACKWARD` to `OrderCreated` and `FORWARD` to `CustomerRegistered`; accepts HTTP 200/204/409; then GETs the rules to verify | One-time rule attach per fresh registry. |

**The mental model:** PR = *ask permission* (dry-run gate). Merge = *make it real* (register a
new version). The artifact's compatibility rule is what makes the gate say no to breaking changes.

```
        ┌─────────────┐   PR    ┌────────────────────────┐  block ❌
        │ schema edit │ ───────▶│ schema-compat-check     │──────────▶ fix the PR
        └─────────────┘         │ (verify -Pcompat-check) │
                                │  dryRun against :8080   │  pass ✅
                                └────────────────────────┘────┐
                                                               ▼ merge to main
                                                  ┌────────────────────────┐
                                                  │ schema-register         │
                                                  │ (apicurio-registry:     │
                                                  │  register, idempotent)  │──▶ new version in registry
                                                  └────────────────────────┘
```

---

## 4. Decision matrix: change type → outcome

`Compat gate` = the PR check / `verify -Pcompat-check` result. `Register` = what happens
post-merge / on `apicurio-registry:register`.

### Protobuf — `events.orders / OrderCreated` (`PROTOBUF`)

| Change | Backward-compatible? | Compat gate (PR) | Register (merge) | Notes |
|---|---|---|---|---|
| Add `optional <type> <name> = <new#>;` | ✅ Yes | **PASS** | New version | Old consumers ignore unknown fields (proto3). Use a **new, never-used field number**. |
| Add a non-`optional` scalar field (new #) | ✅ Yes | **PASS** | New version | proto3 scalars are implicitly defaultable; readers tolerate absence. Prefer `optional` for explicit presence. |
| Change an existing field's type / wire type | ❌ No | **FAIL** (rule violation) | (blocked) | e.g. `order_id` `string`→`int64`. Wire-type mismatch corrupts existing messages. |
| Reuse an existing field number for a new field | ❌ No | **FAIL** (rule violation) | (blocked) | Field numbers are the contract; never repurpose them. |
| Remove / renumber an existing field | ❌ No | **FAIL** (rule violation) | (blocked) | Reserve the number/name instead if you must drop it. |

### JSON Schema — `events.customers / CustomerRegistered` (`JSON`, **FORWARD** rule)

| Change | Forward-compatible? | Compat gate (PR) | Register (merge) | Notes |
|---|---|---|---|---|
| Add a property **not** in `required` | ✅ Yes | **PASS** | New version | Apicurio treats adding a property as a "narrowing" — valid under FORWARD (an old reader ignores the new field), rejected under BACKWARD. This is why the artifact uses FORWARD. |
| Add a property **to** `required` | ❌ No | **FAIL** (rule violation) | (blocked) | A consumer on the old schema can't satisfy a newly-required field → FORWARD-incompatible. |
| Change the type of an existing field | ❌ No | **FAIL** (rule violation) | (blocked) | Old/new readers disagree on the type. |
| Remove a property | ❌ No | **FAIL** (rule violation) | (blocked) | Consumers expecting it break. |

> The artifacts carry **different** rules: `OrderCreated` (Protobuf) is **`BACKWARD`** ("a
> consumer on the new schema can read data written by older schemas"); `CustomerRegistered`
> (JSON Schema) is **`FORWARD`** ("a consumer on the old schema can read data written by newer
> schemas"). Adding a JSON property only passes under FORWARD — see the note above. Each rule is
> what the gate enforces for its artifact.

---

## 5. The reusable playbook (dynamic template)

Fill in the placeholders, then run. This is the part an agent should drive.

| Placeholder | Protobuf value | JSON Schema value |
|---|---|---|
| `<MODULE>` | `order-contracts` | `customer-contracts` |
| `<SCHEMA_FILE>` | `order-contracts/src/main/resources/schemas/order-created.proto` | `customer-contracts/src/main/resources/schemas/customer-registered.json` |
| `<GROUP>/<ARTIFACT>` | `events.orders / OrderCreated` | `events.customers / CustomerRegistered` |

**Steps**

1. **Decide the expected outcome** from the [decision matrix](#4-decision-matrix-change-type--outcome)
   for the `<FIELD>` change you're about to make. Note it as PASS or FAIL before running.

2. **Edit `<SCHEMA_FILE>`** — add/change `<FIELD>`.
   - Protobuf: add a line like `optional string <field_name> = <next_unused_number>;` inside
     `message OrderCreated { … }`. Field numbers in use today: **1–9** → next free is **10**.
   - JSON Schema: add a `"<field_name>": { "type": "…" }` entry under `properties`. To keep it
     FORWARD-compatible (this artifact's rule), **do not** add it to the `required` array.

3. **Compile / regenerate** to catch syntax errors early:
   ```bash
   ./mvnw -pl <MODULE> compile
   ```

4a. **Local equivalent — run the compat gate** (read-only dry-run against the standing registry):
   ```bash
   ./mvnw -pl <MODULE> verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
   ```

4b. **Real CI — run the gate via a PR:**
   ```bash
   git checkout -b schema/<short-description>
   git add <SCHEMA_FILE>
   git commit -m "schema(<MODULE>): add <FIELD>"
   git push -u origin schema/<short-description>
   gh pr create --fill         # or open the PR in the UI
   ```
   Then watch the **Schema Compatibility Check** run:
   ```bash
   gh pr checks --watch
   gh run view --log           # to read the Maven output
   ```

5. **Interpret the result:**
   - **PASS** → `BUILD SUCCESS`, no rule-violation error in the log; PR check is green.
   - **FAIL** → `BUILD FAILURE` with `Registry rule validation failure: RuleViolationException`
     (the artifact's compatibility rule rejecting the change); PR check is red and merge is blocked.
   - If this contradicts your step-1 expectation, the schema edit isn't what you thought —
     re-read the matrix.

6. **Success path only — register the new version:**
   - **Real CI:** merge the PR. The **Schema Registration** workflow runs on `main` and adds a
     new version (idempotent).
   - **Local equivalent:**
     ```bash
     ./mvnw -pl <MODULE> apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
     ```
   - Confirm the new version landed (see [§7](#7-reading-outcomes--troubleshooting)).

7. **Clean up** — pick the branch that matches your tag:
   - 🟢 **GENUINE:** keep the edit. Ensure it's committed + registered (steps 4b/6). Done.
   - 🟡 **THROWAWAY:** revert the working tree and discard the branch:
     ```bash
     git restore <SCHEMA_FILE>
     git checkout main            # if you branched
     git branch -D schema/<short-description> 2>/dev/null || true
     ```
     The registry is untouched by a dry-run gate, so nothing to undo there. If you *registered*
     a throwaway version by mistake, delete it via REST (see [§7](#7-reading-outcomes--troubleshooting)).

---

## 6. Worked examples

Concrete instances of the playbook. Each shows the exact edit, the local command + expected
console signature, the equivalent CI trigger, and cleanup.

### EX-1 — Protobuf: add an optional field 🟢 SUCCESS / GENUINE

**Edit** `order-contracts/src/main/resources/schemas/order-created.proto` — add field **10**
(fields 1–9 are taken):

```diff
 message OrderCreated {
   ...
   optional string promo_code  = 8;
   optional string notes = 9;
+  // v3: optional gift message (BACKWARD-compatible addition — field 10).
+  optional string gift_message = 10;
 }
```

**Local gate:**
```bash
./mvnw -pl order-contracts compile
./mvnw -pl order-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
```
Expected signature: `BUILD SUCCESS`, no `INCOMPATIBLE`. (This mirrors how `promo_code` (8) and
`notes` (9) were added before.)

**Real CI:** PR touching `order-contracts/**` → *Schema Compatibility Check* is **green** →
merge → *Schema Registration* registers `OrderCreated` v-next.

**Register (local):**
```bash
./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
```

**Cleanup:** GENUINE — keep it (commit + register). If you were only rehearsing, treat as
THROWAWAY: `git restore order-contracts/src/main/resources/schemas/order-created.proto`.

---

### EX-2 — Protobuf: change a field's type 🟡 FAILURE / THROWAWAY

The repo ships a ready-made incompatible schema at
`order-contracts/src/test/resources/schemas/order-created-incompatible.proto` — it changes
`order_id` field 1 from `string` to `int64`:

```proto
message OrderCreated {
  int64  order_id    = 1;  // INCOMPATIBLE: was string, now int64 (same field number, incompatible wire type)
  string customer_id = 2;
  ...
}
```

**Local gate** (the `incompatible-demo` profile points the gate at that file):
```bash
./mvnw -pl order-contracts verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080
```
Expected signature: **`BUILD FAILURE`** with
`Registry rule validation failure: RuleViolationException` from the registry. Nothing is written
(dry-run).

**Real CI:** to see the gate reject it on a PR, make the same edit in the *main* schema file and
open a PR — the *Schema Compatibility Check* turns **red** and blocks merge:
```diff
-  string order_id    = 1;
+  int64  order_id    = 1;
```

**Cleanup (THROWAWAY):**
```bash
git restore order-contracts/src/main/resources/schemas/order-created.proto
```
(The `-Pincompatible-demo` run edits nothing, so there's nothing to revert unless you also
changed the main file for the PR demo.)

---

### EX-3 — JSON Schema: add an optional property 🟢 SUCCESS / GENUINE

**Edit** `customer-contracts/src/main/resources/schemas/customer-registered.json` — add a
property and **leave `required` unchanged**:

```diff
     "promoCode": {
       "type": "string",
       "description": "Optional promotional code applied at registration (v2 addition)"
-    }
+    },
+    "referralSource": {
+      "type": "string",
+      "description": "Optional channel the customer came from (v3 addition)"
+    }
   },
   "required": ["customerId", "email", "firstName", "lastName"]
```

**Local gate:**
```bash
./mvnw -pl customer-contracts compile
./mvnw -pl customer-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
```
Expected signature: `BUILD SUCCESS`. Old producers simply omit `referralSource` (deserializes to
`null`).

**Real CI:** PR touching `customer-contracts/**` → check **green** → merge → registration adds
`CustomerRegistered` v-next.

**Register (local):**
```bash
./mvnw -pl customer-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
```

**Cleanup:** GENUINE — keep it. Otherwise THROWAWAY: `git restore` the JSON file.

---

### EX-4 — JSON Schema: add a *required* property 🟡 FAILURE / THROWAWAY

The repo ships
`customer-contracts/src/test/resources/schemas/customer-registered-incompatible.json` — it adds
`accountType` **to the `required` array**:

```json
"required": ["customerId", "email", "firstName", "lastName", "accountType"]
```

**Local gate** (`incompatible-demo` profile points the gate at that file):
```bash
./mvnw -pl customer-contracts verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080
```
Expected signature: **`BUILD FAILURE`** with
`Registry rule validation failure: RuleViolationException` — old data lacks `accountType`, so it
fails the new schema.

**Real CI:** to demo the red PR check, add a new required property in the *main* JSON file and
open a PR:
```diff
+    "accountType": { "type": "string" }
   },
-  "required": ["customerId", "email", "firstName", "lastName"]
+  "required": ["customerId", "email", "firstName", "lastName", "accountType"]
```
→ *Schema Compatibility Check* is **red**, merge blocked.

**Cleanup (THROWAWAY):**
```bash
git restore customer-contracts/src/main/resources/schemas/customer-registered.json
```

---

## 7. Reading outcomes & troubleshooting

**What PASS looks like**
- Maven: `BUILD SUCCESS`; the `compat-gate` execution completes; no `INCOMPATIBLE` text.
- The gate is `dryRun=true`, so **nothing is written** to the registry on a PR — a pass just
  means "this *would* register cleanly."
- PR: the *Schema Compatibility Check* check is green.

**What FAIL looks like**
- Maven: `BUILD FAILURE`; `Registry rule validation failure: RuleViolationException` — the
  artifact's compatibility rule rejecting the change (surfaced as a `RuleViolationException` /
  `RuleViolationProblemDetails` from the registry). For JSON this is typically
  `OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED` (added a property under a too-strict rule) or a
  newly-required field under FORWARD.
- PR: the check is red; merge is blocked.

**Confirm a new version actually landed (success path):**
- UI: open `http://localhost:8888`, navigate to the group/artifact, check the version list.
- REST:
  ```bash
  curl -s http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/versions | cat
  curl -s http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/versions | cat
  ```

**Common failure modes (not schema-related):**

| Symptom | Cause | Fix |
|---|---|---|
| `Connection refused` to `localhost:8080` | Registry not running | `docker compose up -d`; wait for healthy |
| Gate passes but should fail | compatibility rule not attached to the artifact (BACKWARD for orders, FORWARD for customers) | Run the [bootstrap](#2-prerequisites) (step 4); verify with `GET …/rules` |
| PR check never starts | Self-hosted runner offline, or change didn't touch `order-contracts/**` / `customer-contracts/**` | Bring the runner online; confirm the PR edits a watched path |
| Compile fails before the gate | proto/JSON syntax error, or reused proto field number | Fix the schema; `./mvnw -pl <MODULE> compile` |
| Wrong Java / toolchain error | Missing JDK-25 entry in `~/.m2/toolchains.xml` | Add the toolchain (see `CLAUDE.md`) |

**Undo an accidentally-registered throwaway version (rare):**
```bash
# list versions, then delete the unwanted one by its version expression
curl -s   http://localhost:8080/apis/registry/v3/groups/<GROUP>/artifacts/<ARTIFACT>/versions | cat
curl -X DELETE http://localhost:8080/apis/registry/v3/groups/<GROUP>/artifacts/<ARTIFACT>/versions/<VERSION>
```

---

## 8. Quick command reference

```bash
# --- Infra ---
docker compose up -d                                   # registry + seed (v1+v2 + compat rules: orders=BACKWARD, customers=FORWARD)

# --- Compat gate (what a PR runs; read-only dry-run) ---
./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check \
  -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl order-contracts    verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl customer-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080

# --- Register (what a merge to main runs; idempotent write) ---
./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
  -Dapicurio.registry.url=http://localhost:8080

# --- Incompatible rejection demos (MUST fail with INCOMPATIBLE) ---
./mvnw -pl order-contracts    verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080
./mvnw -pl customer-contracts verify -Pincompatible-demo -Dapicurio.registry.url=http://localhost:8080

# --- Attach compatibility rules: orders=BACKWARD, customers=FORWARD (one-time; 409 = already exists) ---
curl -X POST http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules \
  -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'
curl -X POST http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules \
  -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'

# --- Inspect registered versions ---
curl -s http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/versions | cat
curl -s http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/versions | cat

# --- Cleanup a THROWAWAY edit ---
git restore order-contracts/src/main/resources/schemas/order-created.proto
git restore customer-contracts/src/main/resources/schemas/customer-registered.json
```

> **Schema coordinates cheat-sheet**
> - Protobuf: `order-contracts/src/main/resources/schemas/order-created.proto` →
>   `events.orders / OrderCreated` (`PROTOBUF`). Field numbers 1–9 used; next free = **10**.
> - JSON Schema: `customer-contracts/src/main/resources/schemas/customer-registered.json` →
>   `events.customers / CustomerRegistered` (`JSON`). `required`: `customerId, email,
>   firstName, lastName`.
