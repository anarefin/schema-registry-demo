# QA Report: schema-registry-demo — branch use-json-schema-only

**Date:** 2026-06-13  
**Branch:** use-json-schema-only  
**Mode:** Diff-aware (no URL provided)  
**Scope:** JSON Schema conversion of OrderCreated (commits 60aada6, 7493fc3, e5699e6)  
**Duration:** ~12 minutes  
**Services tested:** producer-service :8081, consumer-service :8082, Apicurio Registry :8080  
**Health score:** 52/100

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 1 |
| High     | 0 |
| Medium   | 2 |
| Low      | 2 |

---

## Top 3 Things to Fix

1. **[CRITICAL] Restart services after JSON Schema conversion — and run T-3.4 first** (ISSUE-002)
2. **[MEDIUM] OrderController emits a misleading validation error listing all pre-checked fields even when only one is missing** (ISSUE-001)
3. **[MEDIUM] `preWarmErrors: 0` health metric is silently wrong when pre-warm fails** (ISSUE-005)

---

## Issues

---

### ISSUE-001 — Medium | Functional

**Title:** `OrderController` emits a misleading, hardcoded validation error message

**Repro steps:**
1. `POST http://localhost:8081/api/orders` with a valid payload missing only `currency`:
   ```json
   {"orderId":"x","customerId":"c","productId":"p","quantity":1,"totalAmount":9.99}
   ```
2. Response: `400 Schema validation failed: Schema validation failed for events.orders:OrderCreated: Missing or invalid required fields (productId, currency, quantity>0, totalAmount)`
3. Note: `productId` IS present in the request. The error lists it as missing anyway.

**Root cause:** `OrderController.java:48–53` has a manual pre-validation block that throws a hardcoded error string regardless of which specific field fails:
```java
if (request.productId() == null || request.currency() == null
        || request.quantity() == null || request.quantity() <= 0
        || request.totalAmount() == null) {
    throw new SchemaValidationException(
            "events.orders:OrderCreated",
            "Missing or invalid required fields (productId, currency, quantity>0, totalAmount)");
}
```

**Contrast:** `CustomerController` has NO pre-validation block — it delegates directly to `eventPublisher.publish()`, letting `JsonSchemaStrategy` produce accurate per-field errors (e.g., `"$: required property 'email' not found"`).

**Impact:** API callers cannot determine which specific field(s) failed validation without reading source code.

---

### ISSUE-002 — Critical | Functional

**Title:** Services are running pre-conversion (Protobuf-era) bytecode and have NOT been restarted after the JSON Schema conversion

**Evidence:**
- Producer (PID 39009) and consumer (PID 39306) process start times: **Wed Jun 11 8PM** (approx `2026-06-11 20:xx`)
- JSON Schema conversion commits: `60aada6` at `2026-06-11 22:41`, `7493fc3` at `2026-06-11 22:45`
- Services were started BEFORE the conversion commits. The JVM does not reload classes at runtime.
- DLQ message headers confirm old code is active:
  - `X-Schema-Type: PROTOBUF`
  - Stack trace: `ProtobufStrategy.deserialize(ProtobufStrategy.java:69)` — a class that no longer exists in the converted source
- Apicurio Registry confirms: `OrderCreated` artifact still has `artifactType: PROTOBUF` (T-3.4 not yet run)

**What happens when services are restarted WITHOUT running T-3.4:**
1. Producer/consumer start and call `ApicurioClient.fetchByCoordinates("events.orders:OrderCreated:null")`
2. Registry returns metadata: `{artifactType: "PROTOBUF"}`
3. `SchemaType.fromArtifactType("PROTOBUF")` → throws `IllegalArgumentException("Unsupported schema type: PROTOBUF")`
4. Caught as `RegistryUnavailableException`
5. No cached schema → exception propagates
6. First order publish → `500 Internal Server Error`
7. Consumer listener → all OrderCreated messages fail → DLQ

**Required action before restarting:**
```bash
./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
```
(This is outstanding task T-3.4, noted in CLAUDE.md)

---

### ISSUE-003 — Low | Health

**Title:** Consumer health reports DOWN due to a pre-existing poison pill DLQ message from before the conversion

**Evidence:**
```json
"queueDepth": {
  "status": "DOWN",
  "details": {
    "orders.created.dlq.depth": 1,
    ...
  }
}
```
The DLQ message dates from `2026-06-10T14:29:43.710452Z` (before the JSON Schema conversion). It is a poison pill from the Protobuf era with `X-Schema-Type: PROTOBUF` and a `NOT_VALID_PROTOBUF_BYTES` body.

**Impact:** The overall consumer health endpoint reports `status: DOWN`, which could suppress real alerts. The message will never be reprocessed.

**Note:** After restarting with JSON Schema code, this Protobuf message will again fail deserialization (different error, same outcome). It should be purged from the DLQ before restarting.

---

### ISSUE-004 — Low | API Behavior

**Title:** `orderId` and `customerId` sent in request bodies are silently discarded — server always generates new UUIDs

**Evidence:**
- `OrderController.CreateOrderRequest` record does NOT include an `orderId` field (line 95–100)
- The controller generates: `UUID.randomUUID().toString()` for `orderId` (line 57)
- `CustomerController` similarly discards `customerId` from the request
- Sending `"orderId":"my-id-001"` in the request body has no effect

**Impact:** Minor confusion for API consumers who expect idempotent order creation by ID. No functional bug since server-side ID generation is a valid pattern, but the API contract is invisible — no documentation or error if an `orderId` is sent.

---

### ISSUE-005 — Medium | Observability

**Title:** `preWarmErrors: 0` health metric will be wrong after service restart when OrderCreated pre-warm fails

**Root cause:** `SchemaResolver.preWarm(coords)` (line 89–98) catches ALL exceptions internally and has no return value. `CachePreWarmer` calls `schemaResolver.preWarm(coords)` and has no way to detect failure. `preWarmErrors` (`AtomicInteger`) is never incremented.

**When this becomes active:** After the services are restarted with JSON Schema code and before T-3.4 is run. The pre-warm for `events.orders:OrderCreated:null` will fail silently (PROTOBUF type → `RegistryUnavailableException`) but the health endpoint will show `preWarmErrors: 0` and `preWarmCompleted: true`, masking the problem.

**Impact:** The health indicator gives false confidence. The actual error will only surface on the first real message.

---

## Console Health

Producer (`localhost:8081`): No JS/HTTP errors on tested endpoints.  
Consumer (`localhost:8082`): All REST probes clean.  
RabbitMQ management: Healthy, all queues declared, retry topology confirmed.  
Apicurio Registry: Responding normally (HTTP 302 redirect on `/`).

---

## Schema Registration Status (Apicurio)

| Artifact | Group | Type in Registry | Type Expected by Code | Status |
|----------|-------|------------------|-----------------------|--------|
| `CustomerRegistered` | `events.customers` | JSON ✓ | JSON | OK |
| `OrderCreated` | `events.orders` | **PROTOBUF ✗** | JSON | **MISMATCH** |

---

## Happy Path Verification

| Test | Result |
|------|--------|
| `POST /api/orders` (valid JSON Schema payload) | 201 ✓ (but via old Protobuf code) |
| `POST /api/customers` (valid JSON Schema payload) | 201 ✓ |
| `POST /api/orders` (missing required field) | 400 ✓ (misleading error message) |
| `POST /api/customers` (missing required field) | 400 ✓ (accurate error message) |
| `POST /api/orders/poison` | 202 ✓, DLQ +1 |
| Optional v2 fields (promoCode, notes) accepted | 201 ✓ |
| Consumer processes valid messages (queue depth → 0) | ✓ |
| Registry health check on both services | UP ✓ |
| RabbitMQ topology (queues, retries, DLQ) | All queues present ✓ |

---

## Health Score

| Category | Score | Weight | Contribution |
|----------|-------|--------|-------------|
| Console  | 100   | 15%    | 15.0 |
| Links    | 100   | 10%    | 10.0 |
| Visual   | 100   | 10%    | 10.0 |
| Functional | 30  | 20%    | 6.0 |
| UX       | 75    | 15%    | 11.25 |
| Performance | 100 | 10%   | 10.0 |
| Content  | 100   | 5%     | 5.0 |
| Accessibility | 100 | 15% | 15.0 |
| **TOTAL** | | | **~52/100** |

Functional score penalized heavily for ISSUE-002 (critical: services running wrong code) and ISSUE-001 (medium: misleading errors).

---

## Recommended Next Steps (in order)

1. **Run T-3.4** — register the JSON Schema version of `OrderCreated`:
   ```bash
   ./mvnw -pl order-contracts apicurio-registry:register -Dapicurio.registry.url=http://localhost:8080
   ```
2. **Purge the old Protobuf DLQ message** before restarting (via RabbitMQ management UI or `rabbitmqadmin purge queue name=orders.created.dlq`)
3. **Restart both services** — the new JAR (built 2026-06-12) contains JSON Schema code
4. **Fix ISSUE-001** — remove the hardcoded pre-validation in `OrderController` or make it produce a per-field error
5. **Fix ISSUE-005** — have `SchemaResolver.preWarm()` return a boolean or throw so `CachePreWarmer` can increment `preWarmErrors`

