# schema-messaging-core — best-practices review (JSON-Schema-only POC)

## Context

The POC has dropped Protobuf and is **JSON Schema only** (branch `fix/review-findings`
already deleted `ProtobufStrategy` + its test and collapsed `SchemaType` to a single
`JSON` value). This review is a best-practices pass over `schema-messaging-core` with a
concrete gap list to fix.

Overall: the library is in good shape — clean separation, immutable models, documented
failure model, sane caching, a strong beginner guide (`docs/schema-messaging-core-guide.md`).
The gaps below are real but mostly surgical. No rewrite warranted.

---

## Findings (ranked)

### Correctness / forensics

**F1 — `X-Failure-Reason` carries the routing decision, not the exception class.**
`EventConsumerSupport.populateFailureHeaders` sets `FAILURE_REASON = decision.name()`
("DLQ_DIRECT"/"RETRY"). The guide §7.2 says it is the *exception class name*. On a real
DLQ triage you lose the single most useful field. `DlxRoutingIT` only asserts `isNotBlank()`,
so the drift is untested.
Fix: set `FAILURE_REASON = cause.getClass().getName()`; add a dedicated `X-Failure-Decision`
header if the routing verdict is still wanted. Strengthen the IT assertion to the class name.
`EventConsumerSupport.java:77`.

**F2 — `ApicurioClient` swallows all non-`ApiException` as `RegistryUnavailableException`.**
Both fetch methods end with `catch (Exception e) -> RegistryUnavailableException`. A genuinely
permanent error (e.g. `SchemaType.fromArtifactType` throwing `IllegalArgumentException` on an
unsupported registry artifact type, or an NPE) is mislabelled TRANSIENT and retried 3× before
DLQ. Narrow the catch: let unsupported-type / programming errors surface as permanent.
`ApicurioClient.java:70-72, 100-102`, `SchemaType.java:22`.

### DRY / architecture

**F3 — The retry ladder is defined in three places across modules.**
Tier *suffixes* live in `RetryTierSuffixes` (core), tier *delays* in
`SchemaMessagingConsumerAutoConfiguration` `@Value` fields (core), and the actual
*queues/bindings* in each contracts module's `RetryTopologyFactory`. The brand-new
`RetryTierAlignmentTest` exists only to catch drift between them.
**Constraint (verified):** `order-contracts`/`customer-contracts` are *deliberately
self-contained with no dependency on core* (pom comments + `RetryTopologyFactory.tierSuffix`
"keep aligned" note). So we do NOT consolidate cross-module. **Decision D2:** collapse only
core's *internal* duplication (`RetryTierSuffixes` + the `@Value` delay fields → one
`RetryTopologyProperties` record holding `List<RetryTier>{idx,suffix,delay}`); contracts stay
self-contained; `RetryTierAlignmentTest` remains the executable cross-module contract.

**F4 — Idempotency is hand-wired in every listener, not enforced by the framework.**
`CustomerEventListener` and `OrderEventListener` both repeat the identical
`alreadyProcessed(...)` early-return + `markProcessed(...)`-on-success dance. The core lib
hands out the `IdempotencyFilter` bean but never invokes it. A listener that forgets the
pattern silently loses dedup ("systems over heroes"). Move it into an advice in the listener
container chain (sibling to `DlxRoutingAdvice`) so dedup is automatic and unforgettable.

**F5 — Config style is inconsistent.** Cache tuning uses a typed
`@ConfigurationProperties record ApicurioCacheProperties`; retry tiers + exchange names use
scattered `@Value` field injection in `SchemaMessagingConsumerAutoConfiguration`. Fold the
retry/exchange config into a `RetryTopologyProperties` record (pairs naturally with F3).

### Code quality

**F6 — `SchemaResolver.resolveByCoordinates` does a redundant `getIfPresent` then `get`.**
On a `LoadingCache`, `get(coords)` already returns the cached value when present. The
preceding `getIfPresent` short-circuit is dead code. `SchemaResolver.java:78-84`.

**F7 — `EventPublisher.publish` re-resolves the TypeMapping the converter already resolved.**
`publish` calls `findByJavaType` only to read the routing key, then `converter.toMessage`
resolves the same mapping again. Minor double lookup; acceptable but worth noting.
`EventPublisher.java:43` + `SchemaAwareMessageConverter.java:70`.

### Performance (hot path)

**F8 — Double JSON pass on serialize and deserialize.**
`JsonSchemaStrategy.serialize` writes the payload to bytes, then `validate` re-parses those
bytes with `readTree`. `deserialize` validates (parse #1) then `readValue` (parse #2). Both
paths parse the same JSON twice per message. Validate the `JsonNode` once and reuse it
(`valueToTree` on produce; `readTree` then `convertValue`/`treeToValue` on consume).
`JsonSchemaStrategy.java:65-93`.

**F9 — `JsonSchemaFactory.getInstance(V202012)` rebuilt on every compiled-cache miss.**
Make the factory a `static final`. Micro-cleanup. `JsonSchemaStrategy.java:101`.

### JSON-only cleanup (the headline decision)

**F10 — Multi-format machinery remains after Protobuf removal.**
`SerializationStrategy` SPI, the `Map<SchemaType, SerializationStrategy>` dispatch + fail-fast
loop in the converter, the single-value `SchemaType` enum, `IncompatibleSchemaTypeException`,
and the `fromArtifactType` switch all exist to support a second format that no longer exists.
**Decision D1: keep the SPI as a deliberate, documented extension seam** (low churn, correct
shape for a future Avro). Action is documentation only: add a class-level note on
`SerializationStrategy` and `SchemaType` marking them the intentional single-format seam, so a
future reader doesn't mistake the single-entry dispatch for dead code. The header-type-mismatch
guard stays — it rejects a stale `PROTOBUF` producer crisply.

### Test coverage

**F11 — `DlxMessageRecoverer.recover` routing branches have no fast unit test in core.**
The riskiest logic (retry-vs-DLQ, `retryCount >= length` cutover, count increment, tier-suffix
routing-key construction) is only exercised by `DlxRoutingIT` (Testcontainers, consumer-service).
Add a Surefire unit test with a mocked `RabbitTemplate` asserting exchange + routing key +
incremented header per branch.

---

## Fix plan (ordered — all findings in scope, D3 = boil the ocean)

Sequence by blast radius: correctness first, cleanups, then structural. Make-the-change-easy
ordering — F5 lands the `RetryTopologyProperties` record before F3 reads from it.

1. **F1** — `FAILURE_REASON = cause.getClass().getName()`; add `X-Failure-Decision` for the
   routing verdict. Tighten `DlxRoutingIT` to assert the class name.
   `EventConsumerSupport.java:77`, `SchemaMessageHeaders.java`, `DlxRoutingIT.java:125`.
2. **F2** — narrow `ApicurioClient` catch blocks so unsupported-type / programming errors are
   not laundered into `RegistryUnavailableException`. `ApicurioClient.java`, `SchemaType.java`.
3. **F6** — drop the redundant `getIfPresent` in `resolveByCoordinates`. `SchemaResolver.java:78`.
4. **F9** — hoist `JsonSchemaFactory` to `static final`. `JsonSchemaStrategy.java`.
5. **F8** — validate the `JsonNode` once and reuse it on both serialize and deserialize paths;
   keep `JsonSchemaStrategyTest` green. `JsonSchemaStrategy.java:65-93`.
6. **F11** — new Surefire `DlxMessageRecovererTest` with a mocked `RabbitTemplate`: assert
   exchange + routing key + incremented `X-Retry-Count` for DLQ-direct, retry-tier, and
   retries-exhausted branches.
7. **F5** — introduce `RetryTopologyProperties` record (`List<RetryTier>{idx,suffix,delay}` +
   exchange names), replacing the `@Value` scatter in `SchemaMessagingConsumerAutoConfiguration`.
8. **F3** — collapse core's `RetryTierSuffixes` into `RetryTopologyProperties` as the single
   *internal* source of truth. Contracts stay self-contained; `RetryTierAlignmentTest` remains
   the cross-module contract (update it to read the new core structure).
9. **F4** — `IdempotencyAdvice` (sibling to `DlxRoutingAdvice`) in the listener container chain;
   remove the hand-wired dedup from `CustomerEventListener` / `OrderEventListener`. Add a unit
   test for skip-on-duplicate / mark-on-success.
10. **F7** — minor: have the converter expose the resolved routing key (header or return) so
    `EventPublisher` stops re-resolving the mapping. Lowest priority; skip if it adds friction.
11. **F10** — doc-only: class-level notes on `SerializationStrategy` + `SchemaType` marking the
    intentional single-format seam.

## Resolved decisions

- **D1 — SPI:** keep as documented seam (doc-only, F10).
- **D2 — Retry DRY:** contracts stay self-contained; collapse core-internal duplication into
  `RetryTopologyProperties`; `RetryTierAlignmentTest` is the cross-module contract (F3/F5).
- **D3 — Scope:** everything (F1-F11).

## Verification

- `./mvnw -pl schema-messaging-core test` — Surefire unit tests stay green (incl. new
  `DlxMessageRecovererTest` + idempotency-advice test).
- `./mvnw -pl schema-messaging-core,consumer-service verify` — Testcontainers ITs
  (`DlxRoutingIT`, `RetryTierAlignmentTest`) confirm topology + failure routing end to end.
- Manual: `POST /api/orders/poison` → message lands on DLQ with `X-Failure-Reason` = the
  exception class name (validates F1).
