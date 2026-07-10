# Architecture improvements from `spec/architecture-review.html` (Candidates 1 + 3)

## Context

`spec/architecture-review.html` (10 Jul 2026, `improve-codebase-architecture`) rates five
refactoring candidates against the current post-ADR-0004 codebase. Two are rated "Strong":
**Candidate 1** (contracts-owned `TypeMapping` auto-configuration) and **Candidate 3**
(declarative failure classification). The other three ("worth exploring": per-event metadata
registry, unified schema bootstrap module, shared naming-conventions module) are explicitly
out of scope for this plan — parked as backlog, not detailed here.

This plan was produced via a `/grill-with-docs` interview that resolved two real conflicts the
review's own amber-box notes flagged but didn't settle:

1. Candidate 1 as described is *impossible* under the current rules: `spec/contract-owned-amqp-topology.md`
   Guiding Principle 2 bans a `contracts ↔ core` dependency **in either direction**, machine-enforced
   identically in `order-contracts/pom.xml` and `customer-contracts/pom.xml` (`bannedDependencies`
   excluding `schema-messaging-core`). `TypeMapping` — the exact thing Candidate 1 wants contracts to
   register — lives in `schema-messaging-core`. **Decision: amend Principle 2 to one-directional**
   (contracts → core allowed; core → contracts still banned and still enforced). This reverses a
   deliberately-written, documented guiding principle, so it gets its own ADR (see below).
2. Candidate 3's "declarative failure classification" isn't just a style cleanup — it's a live bug.
   `SchemaAwareMessageConverter.strategyFor()` (`schema-messaging-core/.../converter/SchemaAwareMessageConverter.java:153`)
   throws a bare `org.springframework.amqp.support.converter.MessageConversionException` with no
   cause chain. `EventConsumerSupport.classify()` only recognizes permanent failures by walking the
   cause chain for the six `SchemaMessagingException` subtypes in a hand-maintained `PERMANENT_EXCEPTIONS`
   `Set` — so this exception (and any Spring-internal `MessageConversionException` not wrapping our
   hierarchy) falls through to the conservative `RETRY` default: a message that can never succeed
   burns the full 5s/30s/5m retry ladder before landing on the DLQ anyway. **Decision: blanket-classify
   any `MessageConversionException` as permanent**, and replace the hand-maintained `Set` with a
   `PermanentFailure` marker interface so future exception types self-classify instead of requiring a
   second edit that's easy to forget.

Goal of this plan: land both candidates, with the minimum documentation drift needed so `CONTEXT.md`,
`CLAUDE.md`, the specs, and the ADRs stay accurate to the resulting code.

---

## Candidate 1 — Contracts-owned `TypeMapping` auto-configuration

### Code changes

- **`order-contracts/pom.xml`**: remove the `maven-enforcer-plugin` `bannedDependencies` exclude for
  `com.example:schema-messaging-core` (currently lines ~156-180); add `schema-messaging-core` as a
  regular compile dependency.
- **`customer-contracts/pom.xml`**: same (exclude currently at lines ~170-177).
- **New**: `order-contracts/src/main/java/com/example/contracts/orders/topology/OrderTypeMappingAutoConfiguration.java`
  — a self-activating `@AutoConfiguration` (sibling to `OrderTopologyAutoConfiguration`, same package)
  that declares the three `TypeMapping` beans currently duplicated in
  `producer-service/.../config/OrderContractsConfiguration.java` and
  `consumer-service/.../config/OrderContractsConfiguration.java` (both files are byte-for-byte
  identical today — confirmed by inspection). Register it in
  `order-contracts/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  (currently one line; add a second).
- **New**: `customer-contracts/.../topology/CustomerTypeMappingAutoConfiguration.java` — same pattern,
  mirrored from `producer-service`/`consumer-service`'s `CustomerContractsConfiguration.java`. Register
  in `customer-contracts`'s `AutoConfiguration.imports`.
- **Delete**: the four now-redundant shallow adapters —
  `producer-service/src/main/java/com/example/producer/config/OrderContractsConfiguration.java`,
  `producer-service/.../config/CustomerContractsConfiguration.java`,
  `consumer-service/src/main/java/com/example/consumer/config/OrderContractsConfiguration.java`,
  `consumer-service/.../config/CustomerContractsConfiguration.java`.
- No changes needed to `TypeMappingRegistry`/`SchemaMessagingAutoConfiguration` — they already collect
  all `TypeMapping` beans from the Spring context regardless of which module/jar defined them.

### New ADR: `docs/adr/0005-contracts-may-depend-on-core.md`

Per the skill's ADR criteria (hard to reverse, surprising without context, real trade-off — this
qualifies on all three), record the Principle 2 reversal:

```md
# ADR-0005: Contracts May Depend on Core (One-Directional)

## Status

Accepted.

## Context

ADR-0002 / `spec/contract-owned-amqp-topology.md` Guiding Principle 2 banned any
`contracts ↔ core` dependency, in either direction, machine-enforced via `maven-enforcer-plugin`
on all three modules. The architecture review (`spec/architecture-review.html`, Candidate 1)
found that eliminating the four duplicated `TypeMapping` configuration classes in
producer-service/consumer-service requires `order-contracts`/`customer-contracts` to reference
`TypeMapping`, `SchemaCoordinates`, and `SchemaType` from `schema-messaging-core` — which the
bidirectional ban explicitly forbids.

## Decision

Amend Principle 2 to one-directional: contracts may depend on `schema-messaging-core`; core must
still never depend on any `*-contracts` module (unchanged, still enforced via
`bannedDependencies` in `schema-messaging-core/pom.xml`). The `bannedDependencies` exclude for
`schema-messaging-core` is removed from `order-contracts/pom.xml` and `customer-contracts/pom.xml`.

## Consequences

Every future `*-contracts` module gains the *option* (not obligation) to depend on all of
`schema-messaging-core`, not just the `TypeMapping` package — Maven dependency granularity is
per-module. This trades the fully-symmetric isolation of contracts and core for eliminating
duplicated `TypeMapping` wiring across services. `core ↛ contracts` remains true and enforced;
only the reverse direction opened.
```

### Documentation updates

- **`spec/contract-owned-amqp-topology.md`**: mark Guiding Principle 2 as superseded by ADR-0005
  (leave the original text as historical record per the existing convention used for
  `spec/code-first-schema.md` Principles 4/5), add a cross-reference note.
- **`CONTEXT.md`**: update the `TypeMapping` row in the "Contract terminology" table — replace
  "Registered in producer/consumer `*ContractsConfiguration`" with "Registered in each domain's
  `*-contracts` module via its `*TypeMappingAutoConfiguration`."
- **`CLAUDE.md`**: the "`core ↛ contracts` rule is machine-enforced" bullet already only describes
  the core-side ban (it never mentioned the contracts-side ban that also existed) — so its wording
  stays accurate after this change with no edit needed *there*. Do update the Architecture section's
  producer-service/consumer-service bullet ("no per-service topology glue") to also note TypeMapping
  wiring is now automatic, and the "Project status" header to mention the ADR-0005 amendment.
- **`docs/TUTORIAL.md`**: rewrite the "Services" section (~line 117, currently: "a `*ContractsConfiguration`
  class per service registering one `TypeMapping` bean per event") and §4.3 (~line 210, code sample
  pointing at `producer-service/.../OrderContractsConfiguration.java:31-35`) to reflect the new
  contracts-owned location.

---

## Candidate 3 — Declarative failure classification

### Code changes

- **New**: `schema-messaging-core/src/main/java/com/example/messaging/core/exception/PermanentFailure.java`
  — empty marker interface.
- Modify the six currently-permanent exception classes to `implements PermanentFailure`:
  `SchemaValidationException`, `DeserializationException`, `SerializationException`,
  `IncompatibleSchemaTypeException`, `MissingSchemaHeadersException`, `UnknownSchemaArtifactException`
  (all in `schema-messaging-core/.../exception/`). Leave `SchemaNotFoundException` and
  `InvalidSchemaDefinitionException` untouched — they're startup-only and never reach `classify()`.
- **`EventConsumerSupport.java`**: delete the `PERMANENT_EXCEPTIONS` `Set`. Rewrite `classify()` to
  walk the existing cause chain checking `current instanceof PermanentFailure ||
  current instanceof org.springframework.amqp.support.converter.MessageConversionException` →
  `DLQ_DIRECT`; otherwise `RETRY` (unchanged conservative default).

### Test changes

- **`EventConsumerSupportTest.java`** (`exceptionToDecision()` table): add cases for
  (a) a bare `MessageConversionException` (no cause) → `DLQ_DIRECT`, and (b) a
  `MessageConversionException` wrapping an arbitrary `RuntimeException` → `DLQ_DIRECT`, to lock in
  the fix. Keep the existing bare `RuntimeException` → `RETRY` case as the conservative-default
  control.

### Documentation updates

- **`CLAUDE.md`** "Exception taxonomy (drives routing)" section: note that `MessageConversionException`
  (Spring's own type, not just our hierarchy) is also treated as permanent, and that the
  Permanent/Startup-only split is now expressed via the `PermanentFailure` marker interface rather
  than a hand-maintained `Set` — update the "table-drive... keep them aligned" sentence if the
  mechanism description changes its meaning.

No ADR: this is a bug fix + mechanical refactor (removes a footgun, no architectural boundary moves),
not a hard-to-reverse trade-off — doesn't meet the skill's three-part ADR bar.

---

## Sequencing

The two candidates touch disjoint files (contracts/services vs. `schema-messaging-core`'s exception
package) and can be implemented in either order or in parallel. No shared risk between them.

## Verification

- `./mvnw -pl order-contracts,customer-contracts,schema-messaging-core,producer-service,consumer-service -am install`
  — confirms the enforcer rule change compiles cleanly and no module still references the deleted
  `*ContractsConfiguration` classes.
- `./mvnw -pl schema-messaging-core test -Dtest=EventConsumerSupportTest` — table-driven classify()
  coverage including the new `MessageConversionException` cases.
- `./mvnw -pl producer-service spring-boot:run` and `./mvnw -pl consumer-service spring-boot:run` —
  confirm both services still start and the `TypeMapping` beans resolve correctly with the topology
  auto-configs now sharing the contracts jar (context refresh must not fail — this is the concrete
  regression risk from moving bean definitions).
- `./mvnw verify` (Failsafe/Testcontainers) — `OrderCreatedIT`, `CustomerRegisteredIT`, `DlxRoutingIT`
  exercise the full producer→registry→consumer→DLQ path end to end; confirms Candidate 1's bean
  relocation and Candidate 3's classification change both work under real RabbitMQ.
- Manual: trigger the existing `POST /api/orders/poison` DLQ demo and confirm `X-Failure-Reason`
  still reads `DLQ_DIRECT` (unaffected by these changes, but a good end-to-end smoke check post-refactor).
