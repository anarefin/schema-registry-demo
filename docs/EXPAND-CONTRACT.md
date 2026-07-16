# Expand/contract playbook (Tier 1)

This repo supports **compatible evolution only** — no same-artifact breaking changes, no
`OrderCreatedV2`, no runtime registry fetch. See [ADR-0009](adr/ADR-0009-schema-versioning-model.md)
for the model and [CONTEXT.md](../CONTEXT.md) for glossary terms (`FORWARD`, *tolerant reader*,
*expand/contract*, *version state*).

**Sanctioned path:** add optional field → regenerate → commit schema → FORWARD compat-check →
deploy producer first → consumers adopt → deprecate/remove only with ACK from every handler owner.

---

## Worked example — add `priority` to `OrderCreated`

**Goal:** Ship a new optional `priority` field without DLQ-ing lagging consumers.

Today [`OrderCreated`](../order-contracts/src/main/java/com/example/contracts/orders/OrderCreated.java)
has six required business fields (`orderId`, `customerId`, `productId`, `quantity`, `totalAmount`,
`currency`) plus optional `createdAt`. Add `priority` as **optional** — no `@NotNull` — so the
generator omits it from the schema `required` array.

### Step A — Expand (additive, FORWARD-compatible)

1. **Author** — add the field to the record:

```java
@JsonPropertyDescription("Fulfillment priority hint, e.g. HIGH or NORMAL.")
String priority  // no @NotNull → optional in JSON Schema
```

2. **Regenerate** the schema (never hand-edit `order-created.schema.json`):

```bash
./mvnw -pl order-contracts -am process-classes
```

3. **Commit** the updated `order-contracts/src/main/resources/schemas/order-created.schema.json`
   alongside the Java change.

4. **Drift gate** (offline):

```bash
git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'
```

5. **Compat-check** (CI merge gate; needs a running registry for local runs):

```bash
./mvnw -pl order-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080
```

   Property addition passes under the artifact's **FORWARD** rule.

6. **Deploy producer first** — `producer-service` bumps `order-contracts` and starts emitting
   `priority` when set. Old consumers still on the previous contracts jar:

   - **Validate** against their classpath schema (`additionalProperties: true` — extra props allowed)
   - **Deserialize** via the tolerant reader (`FAIL_ON_UNKNOWN_PROPERTIES=false` on the messaging
     mapper) — Jackson ignores `priority`

   Illustrative wire body:

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "customerId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "productId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
  "quantity": 2,
  "totalAmount": 49.99,
  "currency": "USD",
  "priority": "HIGH"
}
```

   Headers are unchanged — identity is still `(group, artifact)` only:

   - `X-Schema-GroupId: events.orders`
   - `X-Schema-ArtifactId: OrderCreated`
   - `X-Schema-Type: JSON`

   Apicurio may assign integer version *N+1* on register; **runtime never reads it**.

### Step B — Migrate readers

1. List every service with an `@BitsEventHandler` for `OrderCreated` (in this POC:
   `consumer-service` → `OrderEventListener.onOrderCreated`; in a real estate, grep
   `@BitsEventHandler` + `OrderCreated` across repos).
2. Each team bumps `order-contracts`, maps `priority` in handler logic, deploys.
3. Until all teams adopt, lagging consumers from Step A keep working.

### Step C — Contract (remove a field later)

Only when **all** known handlers no longer read the old field (if you are *replacing* one, not
adding):

1. Mark the Java component `@Deprecated` for at least one release cycle.
2. Confirm no producer still emits it and no handler still reads it (deploy-version inventory +
   handler grep).
3. Open a removal PR; run compat-check. If Apicurio **rejects** the removal, that cut is a
   **Tier 2** escalation (new artifact), not a silent break.

Schemas grow monotonically under Tier 1 — expand/contract discipline is mandatory, not advisory.

---

## Contracts PR checklist

Copy into every `*-contracts` PR description:

| Check | Question |
|-------|----------|
| **FORWARD?** | Does this change only *add* optional properties (or other FORWARD-safe edits)? Run `verify -Pcompat-check`. |
| **Optional?** | New record components lack `@NotNull` / `@NotBlank` / etc., so they stay out of `required`. |
| **Producer first?** | Deploy order documented: publisher(s) before consumers. FORWARD assumes producers may lead. |
| **Consumers listed?** | Every `@BitsEventHandler` owner for this event named, with adoption plan or "none lagging". |
| **Schema committed?** | `process-classes` run; generated `*.schema.json` in the same PR as the Java record. |
| **Drift clean?** | `git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'` passes. |

If any box is "no" or "unknown", stop — this is not a Tier 1 change.

---

## Deprecation (governance only — not runtime)

Field-level deprecation in Java uses `@Deprecated` on the record component (Draft-07 has no
`deprecated` keyword). Registry **version state** is a separate, human/CI signal.

### When to flip `DEPRECATED`

After drift evidence shows no consumer still depends on an old schema revision — e.g. fleet on the
new contracts jar, optional `contentHash` telemetry (if ever added) quiet. Do **not** flip on a
calendar alone.

### How — Apicurio REST (humans and CI only)

This runtime **never fetches** registry content. `DEPRECATED` surfaces as a warning header on
**content fetch** — which nothing in producer/consumer JVMs performs. The curl is for operators,
governance dashboards, and CI audit — **not** for services.

```bash
# Replace version integer with the Apicurio-assigned version you intend to retire
curl -X PUT "$REGISTRY/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/versions/3/state" \
  -H 'Content-Type: application/json' \
  -d '{"state":"DEPRECATED"}'
```

States: `ENABLED` / `DISABLED` / `DEPRECATED` (per-version, not per-artifact).

### Owner signal — tickets and CODEOWNERS

Deprecation and removal need explicit ACK from every handler owner:

1. Open a ticket naming the field/version, listing services from the `@BitsEventHandler` inventory.
2. Route through `CODEOWNERS` (or team channel) for each consumer repo.
3. Do **not** expect consumer JVMs to observe `DEPRECATED` — they resolve schemas from the
   contracts jar on the classpath, not from Apicurio.

### Field census cadence

Schemas grow monotonically under Tier 1 (see Step C), so removal candidates have to be found
deliberately — nothing forces the question. Run a **quarterly** census: grep every `*-contracts`
module for `@Deprecated` record components, then cross-reference each hit against the current
`@BitsEventHandler` inventory for that event type. A field becomes a removal candidate once the
census shows no remaining handler reads it and no producer still emits it; open the Step C removal
PR at that point rather than letting `@Deprecated` fields sit un-actioned across multiple censuses.

---

## Anti-example — do not do this

**In-place rename:** changing `orderId` → `id` on the same `OrderCreated` artifact.

- Compat-check **must fail** (FORWARD treats that as narrowed / breaking).
- Old consumers still validate and deserialize `orderId`; new producer emits `id` → validation or
  mapping failure → **DLQ**, not a graceful skip.

**Correct Tier 1 path:** expand — add `id` alongside `orderId`, dual-write both, migrate handlers to
`id`, deprecate `orderId`, remove only when compat-check passes and all owners ACK.

**If expand/contract cannot work** (true hard break, polyglot constraint, etc.): escalate to Tier 2
— new artifact (e.g. `OrderCreatedV2`), parallel handlers, dual-publish. That path is **not**
implemented in this POC; it requires explicit architecture review.

---

## Quick reference

| Action | Command / link |
|--------|----------------|
| Regenerate schemas | `./mvnw -pl order-contracts,customer-contracts -am process-classes` |
| Offline drift gate | `git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'` |
| CI compat gate | `./mvnw -pl order-contracts,customer-contracts verify -Pcompat-check` |
| Register (governance) | `./mvnw -pl order-contracts,customer-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080` |
| Model ADR | [ADR-0009](adr/ADR-0009-schema-versioning-model.md) |
| Glossary | [CONTEXT.md](../CONTEXT.md) |
