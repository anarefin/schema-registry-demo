# Code Review Findings

A full-project code-quality review of the schema-registry-demo POC (Apicurio 3.2.0 +
RabbitMQ + Spring Boot 4.0 / Java 25).

**Scope & framing.** This is a proof-of-concept. The review respects the project's
*intentional, documented* shortcuts (in-memory idempotency, `guest` RabbitMQ credentials,
single-instance registry, deferred 200+ contract auto-discovery) and does **not** flag those
as defects — see [Known limitations](#known-limitations-out-of-scope-for-the-poc). Findings
below were verified against source. Test-coverage gaps are noted only briefly at the end.

This review was produced on the `fix/review-findings` branch, which already addresses the
idempotency-filter semantics, the UTF-8 stack-trace truncation, `MessageConversionException`
routing, and retry-tier alignment. Those are **not** re-reported here as open issues.

Severity legend: **High** — correctness/robustness risk · **Medium** — quality issue worth
fixing · **Low** — polish / minor.

---

## A. Correctness & code quality

| ID | Severity | Location | Finding |
|----|----------|----------|---------|
| A1 | Medium | `ApicurioClient.java:62`, `:87-91` | Unclosed `InputStream` |
| A2 | Low | `ApicurioClient.java:68-69`, `:98-99` | Unreachable (dead) catch blocks |
| A3 | Medium | `CustomerController.java:43-54`, `OrderController.java:48-54` | Inconsistent / missing request validation |
| A4 | Low | `OrderController.java:89-93`, `CustomerController.java:60-64` | Duplicated exception handler |
| A5 | Low | `CustomerController.java:60` | Fully-qualified annotation vs. import |
| A6 | Low | `OrderController.java:76` | `202` vs `201` status inconsistency (debatable) |
| A7 | Low | `exception/SchemaMessagingException` + subtypes | Seal the exception taxonomy |
| A8 | Low | `TypeMappingRegistry.findByGroupAndArtifact` | Linear scan over an already-indexed set |

### A1 — Unclosed `InputStream` in ApicurioClient (Medium)

`fetchByGlobalId` and `fetchByCoordinates` read the registry response via
`content.readAllBytes()` but never close the stream:

```java
// ApicurioClient.java:62
InputStream content = registryClient.ids().globalIds().byGlobalId(globalId).get();
byte[] bytes = content.readAllBytes();           // stream never closed
```

`InputStream.readAllBytes()` reads to EOF but does **not** close the stream, so the underlying
HTTP connection may not be released back to the pool. Under sustained consume traffic this can
leak connections.

**Fix:** wrap in try-with-resources in both methods:

```java
try (InputStream content = registryClient.ids().globalIds().byGlobalId(globalId).get()) {
    byte[] bytes = content.readAllBytes();
    return new ResolvedSchema(globalId, schemaType, bytes);
}
```

### A2 — Unreachable catch blocks (Low)

```java
} catch (ApiException e) {
    if (e.getResponseStatusCode() == 404) throw new SchemaNotFoundException(ctx, e);
    throw new RegistryUnavailableException(ctx, e);
} catch (SchemaNotFoundException | RegistryUnavailableException e) {  // ← unreachable
    throw e;
} catch (Exception e) {
    throw new RegistryUnavailableException(ctx, e);
}
```

`SchemaNotFoundException` / `RegistryUnavailableException` are only ever thrown *inside* the
`ApiException` catch, which does not re-enter the `try`. The dedicated catch (lines 68-69 and
98-99) can never execute. Remove it in both methods.

### A3 — Inconsistent / missing request validation (Medium)

The two producer controllers validate input differently, and one not at all:

- `CustomerController.registerCustomer` (`:43-54`) does **no** validation — a body missing
  `email`/`firstName`/`lastName` is mapped into a `CustomerRegistered` with null fields and
  only fails later, deep inside serialization.
- `OrderController.createOrder` (`:48-54`) hand-rolls a null/`quantity<=0` check and throws
  `SchemaValidationException` manually.

**Fix:** use declarative Bean Validation on both request records and `@Valid` on the
parameters — consistent field-level `400`s, no boilerplate:

```java
public record RegisterCustomerRequest(
        @NotBlank @Email String email,
        @NotBlank String firstName,
        @NotBlank String lastName,
        String phoneNumber) {}

public void registerCustomer(@Valid @RequestBody RegisterCustomerRequest request) { ... }
```

```java
public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotBlank String productId,
        @NotNull @Positive Integer quantity,
        @NotNull Double totalAmount,
        @NotBlank String currency) {}
```

### A4 — Duplicated exception handler (Low)

`OrderController` (`:89-93`) and `CustomerController` (`:60-64`) carry an identical
`@ExceptionHandler(SchemaValidationException.class)`. Extract to a single
`@RestControllerAdvice`. While there, add a handler for `HttpMessageNotReadableException` so
malformed JSON returns a clear `400` instead of the framework default.

### A5 — Annotation style inconsistency (Low)

`CustomerController.java:60` uses the fully-qualified
`@org.springframework.web.bind.annotation.ExceptionHandler` while `OrderController` imports it.
Import it for consistency (moot if A4 is applied).

### A6 — Status-code inconsistency (Low, debatable)

`/api/orders/poison` returns `202 ACCEPTED` (`OrderController.java:76`) while the real create
endpoints return `201`. `202` is defensible for a fire-and-forget demo endpoint, but the
inconsistency is worth a deliberate decision.

### A7 — Seal the exception taxonomy (Low)

`SchemaMessagingException` has a fixed, known set of subtypes
(`SchemaNotFoundException`, `SchemaValidationException`, `DeserializationException`,
`SerializationException`, `IncompatibleSchemaTypeException`, `RegistryUnavailableException`)
that drive the retry-vs-DLQ decision in `EventConsumerSupport`. Declaring the base `sealed`
documents the closed hierarchy and enables an exhaustive pattern-switch in `classify()` in
place of the `instanceof` chain — a natural fit on Java 25.

### A8 — Linear scan in TypeMappingRegistry (Low)

`findByGroupAndArtifact` streams the full mapping list with a filter, even though the registry
already builds a `byCoordinates` map. Build a `"group:artifact"` lookup at construction and use
it for O(1) resolution. Negligible at POC scale; trivial to fix.

---

## B. Build, CI & scaling

| ID | Severity | Location | Finding |
|----|----------|----------|---------|
| B1 | Low | `schema-messaging-core/pom.xml` enforcer | Ban not future-proof (no wildcard) |
| B2 | Medium | both `*-contracts` `amqp/` | Duplicated `RetryTopologyFactory` / `EventExchanges` |
| B3 | Medium | `.github/workflows/*` | Hardcoded contract lists (known limitation) |
| B4 | Low | producer & consumer config | Duplicated `TypeMapping` `@Bean` wiring |
| B5 | Low | CI | No unit-test gate; no dependency CVE scan |

### B1 — Enforcer ban not future-proof (Low)

The `core ↛ contracts` rule lists each contract module explicitly in `bannedDependencies`.
A new contract module would not be covered until someone updates the list. Use a wildcard:

```xml
<exclude>com.example:*-contracts</exclude>
```

### B2 — Duplicated retry topology (Medium)

`RetryTopologyFactory` is byte-for-byte identical in `order-contracts` and `customer-contracts`
(only the package differs), and its `tierSuffix` duplicates core's `RetryTierSuffixes` — kept
in sync only **reactively** by `RetryTierAlignmentTest`. `EventExchanges` is likewise
duplicated.

```java
// duplicated in both contracts modules:
public static String tierSuffix(int tier) {
    // Keep aligned with schema-messaging-core RetryTierSuffixes
    return switch (tier) { case 0 -> "5s"; case 1 -> "30s"; case 2 -> "5m"; default -> "t" + tier; };
}
```

The clean home is `schema-messaging-core` (contracts may depend on core; the enforcer rule only
forbids the reverse). Extracting it removes the duplication and makes the alignment test
unnecessary. The alignment test is an acceptable POC stopgap, so this is a *quality* item, not
a blocker — see also B3/B4 and the scaling doc (C1).

### B3 — Hardcoded contract lists in CI (Medium, known limitation)

`schema-compat-check.yml` and `schema-register.yml` hardcode
`-pl order-contracts,customer-contracts`, and `schema-governance-bootstrap.yml` has one `curl`
per artifact. Each new contract requires editing the workflows. Auto-discovery
(`find . -path '*-contracts/pom.xml'`) would scale this. Documented as a limitation, not a
POC must-fix.

### B4 — Duplicated TypeMapping wiring (Low)

Producer and consumer each declare near-identical `TypeMapping` `@Bean` configuration; the same
mapping is maintained in two services. A scaling concern; document only at POC scale.

### B5 — CI gaps (Low)

CI runs `verify` (Failsafe/Testcontainers) but has no fast `mvn test` (Surefire) gate and no
dependency-vulnerability scan. Cheap, POC-appropriate adds: a unit-test stage and an OWASP
`dependency-check` run.

---

## C. Documentation & API

| ID | Severity | Location | Finding |
|----|----------|----------|---------|
| C1 | Medium | `docs/CONTRACT-SCALING-STRATEGY.md` (deleted) | Restore the scaling-debt doc |
| C2 | Low | auto-config / headers / cache props | Javadoc gaps |
| C3 | Low | `docs/TODO.md` | Verbose; duplicates the plan doc |

### C1 — Restore the deleted scaling doc (Medium)

`docs/CONTRACT-SCALING-STRATEGY.md` was deleted on this branch, but it correctly captured the
debt behind B2–B4 (per-contract onboarding tax, copy-paste in profiles/CI/TypeMappings).
Deleting it hides a real, accurate limitation. Recommend restoring it — retitled e.g.
"Known Limitations at Scale" — so the debt stays visible and tracked rather than lost.

### C2 — Javadoc gaps (Low)

- Auto-configuration classes (`SchemaMessagingAutoConfiguration`,
  `SchemaMessagingConsumerAutoConfiguration`) don't summarize which beans they wire, their
  `@ConditionalOn*` activation, or default property values.
- `SchemaMessageHeaders` defines the header constants but has no single reference table of all
  `X-Schema-*` / `X-Message-*` / `X-Failure-*` headers and their semantics.
- `ApicurioCacheProperties` lacks tuning guidance (TTL / refresh / size meaning).

### C3 — `docs/TODO.md` verbosity (Low)

Largely duplicates `docs/POC-Implementation-Plan.md` with added file paths. Consider trimming to
avoid two sources drifting.

---

## D. Test coverage (de-prioritized — backlog pointer)

Not a focus of this review, noted for completeness: the recently added `EventConsumerSupport`
paths (`MessageConversionException` classification, UTF-8 truncation back-off) and the
refactored `IdempotencyFilter` two-phase contract are exercised by `DlxRoutingIT` but lack
isolated unit tests; the auto-configuration classes are covered only indirectly via
`@SpringBootTest`. Worth a small follow-up if/when test coverage becomes a priority.

---

## Known limitations (out of scope for the POC)

These are **intentional, documented** POC shortcuts (see the README "POC-only" table) and are
deliberately **not** treated as defects:

- In-memory (Caffeine) idempotency — not safe across multiple consumer instances.
- `guest` / `guest` RabbitMQ credential defaults in `application.yml`.
- Single-instance registry assumptions and cache-TTL tuning.
- Manual, hardcoded contract enumeration instead of auto-discovery at 200+ contracts
  (the subject of the scaling doc in C1).

---

## Suggested ordering

1. **Quick correctness/cleanups:** A1 (stream close), A2 (dead code), B1 (enforcer wildcard).
2. **Consistency:** A3 (Bean Validation), A4/A5 (`@RestControllerAdvice`).
3. **Quality/idiom:** A7 (sealed exceptions), B2 (de-duplicate retry topology), A8.
4. **Docs:** C1 (restore scaling doc), C2, C3.
