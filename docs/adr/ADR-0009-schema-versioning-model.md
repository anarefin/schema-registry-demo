# ADR-0009: Schema versioning model — compatible evolution only

**Status:** Accepted — architecture; runtime hardening tracked (see [Open work](#open-work)).  
Not a claim that production hardening is complete.  
**Date:** 2026-07-16  
**Spec:** `spec/12-schema-versioning.md`  
**Related findings:** ARCH-010 (versioning model undocumented), QUAL-003 (tolerant reader — Phase 1), DOC-001 (ADR-0004 deleted but cited)  
**Depends on:** [ADR-0004](0004-local-schema-validation.md) — classpath schema resolution, no runtime registry  
**Supersedes:** nothing

## Context

There is no version anywhere in the authored → generated → registered → wire pipeline.
[`SchemaCoordinates`](../../event-contract-kit/src/main/java/com/example/amqp/topology/mapping/SchemaCoordinates.java)
is `(groupId, artifactId)`.
[`@GenerateSchema`](../../event-contract-kit/src/main/java/com/example/amqp/topology/mapping/GenerateSchema.java)
is a bare marker with no attributes. Generated schemas carry `$id: urn:example:schema:<Type>` and
nothing else identifying a revision. Every `<artifact>` in the contracts POMs omits `<version>`, so
Apicurio auto-assigns integers that nothing in this repo reads. Only three `X-Schema-*` headers
reach the wire (`GroupId`, `ArtifactId`, `Type` —
[`SchemaMessageHeaders.java:13-15`](../../schema-messaging-core/src/main/java/com/example/messaging/core/converter/SchemaMessageHeaders.java#L13-L15)).

That absence reads as an oversight. It is not, and three facts reframe it.

**Minor versioning already works.** Apicurio auto-versions each artifact when its content changes,
`ifExists=FIND_OR_CREATE_VERSION` makes registration idempotent, and the FORWARD rule gates every
bump. What the system lacks is not versioning — it is ***major*** versioning.

**Major versioning cannot exist under a FORWARD rule, by construction.** Register a breaking
`OrderCreated` as version 2 of artifact `events.orders:OrderCreated` and the compatibility gate
*rejects it* — correctly, because it *is* incompatible. That gate is the single thing this POC
exists to demonstrate; bypassing it for major bumps would gut the project's premise. A breaking
change is therefore not a version of an artifact. It could only ever be a **new artifact**.

**The reasoning was lost, not absent.** [ADR-0004](0004-local-schema-validation.md) already decided
this, in terms that pre-empt the entire question:

> `X-Schema-GlobalId` and `X-Schema-Version` headers are dropped — nothing computes a real Apicurio
> globalId locally, and there is no "version" concept left once there is exactly one schema per
> build.

> **Lost capability: runtime schema version pinning.** Pinning only made sense against a registry
> that could serve multiple live versions; a locally-baked JAR has exactly one version of each
> schema by construction.

ADR-0004 was deleted in `c0f9738` while still cited by six javadoc/test sites. The felt absence of
versioning is largely the absence of *this paragraph*, not of a mechanism.

## Decision

**Tier 1 only: compatible evolution.** Breaking changes are explicitly **unsupported** — an
escalation, not a workflow. There is no `OrderCreatedV2`, no parallel handler, no version
coexistence on the wire.

Expand/contract (parallel change) is the sanctioned route for an apparently-breaking change: add
the new field alongside the old (additive, passes the FORWARD gate, no major version, no migration
window), migrate readers, then remove the old field once it is provably unread. One hard break
becomes three additive steps the existing machinery already handles with **zero new mechanism**.
This is the loudest sentence in this ADR: **the cheapest breaking change is the one you don't
make.**

### The forced constraint — why a wire version can never gate

This is arithmetic, not preference, and it follows directly from ADR-0004.

A consumer resolves schemas from its **own classpath**
([`LocalSchemaCatalog`](../../schema-messaging-core/src/main/java/com/example/messaging/core/schema/LocalSchemaCatalog.java)).
Suppose the wire carried a version identifier, and a consumer shipping only v1 received a message
stamped v2. It cannot fetch v2 (no runtime registry, by ADR-0004). It cannot fall back to a schema
it does not ship. Its only two options are:

- **Reject** → every producer schema bump DLQs every lagging consumer in the estate. Catastrophic.
- **Ignore the mismatch** and resolve by `(group, artifact)` against its own schema — i.e. exactly
  today's behaviour, with the identifier reduced to a label.

**Therefore the wire version identifier's only possible role is forensic.** Compatibility rests
entirely on two other things: the **FORWARD gate** (CI, build time) and the **tolerant reader**
(runtime). Anything else claiming to enforce versions at runtime in this architecture is
misdescribing itself.

### Per stage

| Stage | Version identity | Mechanism |
|---|---|---|
| **Authoring** | none | Expand/contract discipline; `@Deprecated` field convention |
| **Generation** | none — `$id` stays version-free | Determinism is the goal; tolerant-reader invariant asserted here |
| **Registry** | auto-assigned integer | FORWARD rule; `FIND_OR_CREATE_VERSION`; `canonicalize` |
| **Wire** | `contentHash` (advisory) | Build-time computable; **never gates** |
| **Consume** | none — lookup by `(group, artifact)` | Tolerant reader; hash miss → drift telemetry |
| **Governance** | version state | Drift telemetry → evidence → safe `DEPRECATED` flip |

**Authoring.** No version attribute on `@GenerateSchema`. Field deprecation is a convention:
`@Deprecated` on a record component, surfaced through the generator's `description`. The standard
`deprecated` keyword is unavailable — it arrived in JSON Schema 2019-09, and the draft is pinned to
Draft-07 because Apicurio 3.2.0's everit checker HTTP-500s on 2020-12, which would disable the
FORWARD gate entirely
([`SchemaGenerator` javadoc](../../schema-gen-tools/src/main/java/com/example/schemagen/SchemaGenerator.java)).

**Generation.** `$id` remains `urn:example:schema:<Type>`, version-free. This is correct and must
stay: byte-determinism is the design goal, and a version in `$id` would churn every schema on every
bump. Generation is also where the tolerant-reader invariant is asserted (below).

**Registry.** Versions stay auto-assigned; nothing in code names a version number.
`<canonicalize>true</canonicalize>` is set on contract-module Apicurio register config so
`FIND_OR_CREATE_VERSION` matches on canonical content rather than raw bytes. Generator
byte-determinism remains load-bearing for the offline drift gate; canonicalize closes the
registry-side gap.

**Wire.** If a version identifier is ever added, it must be Apicurio's **`contentHash`** — the hash
of canonical schema content. Of Apicurio's three identifiers, `globalId` (per artifact version) and
`contentId` (per content blob) are **assigned by the registry** and unknowable without calling it;
only `contentHash` is derived from bytes the producer already has on its classpath at build time.
It is **advisory**, per the forced constraint above.

**Consume.** Unchanged: `(group, artifact)` → `TypeMapping` → classpath schema. A `contentHash`
**miss is a signal, not a failure** — *"I consumed a message produced from a schema revision I do
not have."* That is the drift detector.

**Governance.** Apicurio version state is **per-version**:
`PUT /groups/{g}/artifacts/{a}/versions/{v}/state` with `{"state":"DEPRECATED"}`; states are
`ENABLED` / `DISABLED` / `DEPRECATED`. Crucially, `DEPRECATED` signals via **a warning header on the
REST response when content is fetched** — and this runtime never fetches content. **The deprecation
warning reaches nobody.** Version state is governance metadata for humans and CI *only*; it has no
runtime effect here, and documenting otherwise would be false. Aggregated drift telemetry is what
supplies the evidence ("nobody has published that revision for N days") that makes flipping a
version to `DEPRECATED` safe.

### The FORWARD bet is directional

The only recorded reason for FORWARD is mechanical: Apicurio classifies adding a property as
`OBJECT_TYPE_PROPERTY_SCHEMAS_NARROWED`, which BACKWARD rejects and FORWARD accepts. The
*directional* reason matters more and was never written down:

**FORWARD = old schema reads data written by the new schema = the producer upgrades first and
consumers lag safely.** That is the correct bet for integration events with one publisher and many
independently-deployed consumers — which is exactly this estate's shape (see
[ADR-0008](ADR-0008-publisher-owned-messaging-topology.md)). BACKWARD would encode the opposite bet
(consumers first) and is wrong here. The choice is a deployment-order commitment, not a lint
setting.

### The tolerant reader is a named invariant

Under Tier 1, the FORWARD gate is only **half** the compatibility contract. The other half is the
runtime's willingness to read a payload carrying fields it does not know. Both props are
**architectural invariants**, now defended in code (Phase 1 — see [Open work](#open-work)):

1. **Generated schemas emit `"additionalProperties": true`** on every object node
   ([`SchemaGenerator`](../../schema-gen-tools/src/main/java/com/example/schemagen/SchemaGenerator.java)),
   with a parse-tree test that fails if any node carries `false`. Intent is visible in committed
   JSON, not resting on JSON Schema's implicit default. A future "tighten the schemas" change
   adding `additionalProperties: false` would silently convert every forward-compatible message
   into a DLQ'd `SchemaValidationException` — the generator test is the tripwire.
2. **Messaging owns a dedicated tolerant `ObjectMapper`** built inside
   [`JsonSchemaStrategy`](../../schema-messaging-core/src/main/java/com/example/messaging/core/serde/JsonSchemaStrategy.java)
   (`FAIL_ON_UNKNOWN_PROPERTIES=false`) — never a bean, never injected from the app context. The
   `@ConditionalOnMissingBean(ObjectMapper.class)` fallback in
   [`SchemaMessagingAutoConfiguration`](../../schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingAutoConfiguration.java)
   remains for non-messaging Jackson 2 needs only. An application `@Bean ObjectMapper` with
   `FAIL_ON_UNKNOWN_PROPERTIES=true` must not displace messaging deserialization; defended by
   [`MessagingObjectMapperIsolationTest`](../../schema-messaging-core/src/test/java/com/example/messaging/core/config/MessagingObjectMapperIsolationTest.java).

## Rejected alternatives

### Tier 2 — breaking changes as new artifacts + parallel handlers

The design was worked through: a breaking change becomes a new Java type (`OrderCreatedV2`), its own
`artifactId`, its own schema file, its own independent compat lineage; both types mapped and handled
concurrently; queue-level isolation falling out free from `@BitsEventHandler` discovery, since a new
type means a new routing key means a new queue that a v1-only consumer never binds.

Rejected because the estate does not need it, and because the machinery's cost is permanent while
the need is hypothetical. Every breaking change encountered so far decomposes into expand/contract
steps. Should this be revisited, the two decisions that must be re-made are handled-version dispatch
and sunset enforcement — and **the failure to plan for is not a bad version scheme, it is that the
migration window never closes**: V1 handlers survive for years because nobody can prove nobody
publishes v1 anymore. Do not adopt Tier 2 without the telemetry to answer that question.

### Version as a third `SchemaCoordinates` field

`(groupId, artifactId, version)` is registry-native and Apicurio models it directly. Rejected on two
counts. Mechanically it touches the coordinates record and all seven of its usages, both duplicated
`SchemaFileNaming` copies, the catalog, the registry, and the converter. Fatally, it puts the design
in a fight with its own compat rule: a *breaking* v2 of the same artifact is exactly what the FORWARD
gate exists to reject. Version-in-the-artifact-identity needs none of it.

### Dual-publish during a migration window

The publisher emits both v1 and v2 of a domain fact for the window. Strictly the most *expressive*
option — the publisher holds the source of truth and can always emit a faithful v1 even when v2
dropped a field. Rejected: it solves a Tier 2 problem, costs 2× traffic plus an atomic dual-write,
and the case where its expressiveness matters (v2 destroyed information a consumer still needs) is
the case that should be a conversation, not a codec.

### Library-owned transform (`OrderCreatedV2` → `OrderCreated`)

The contracts module ships a transform written once by the domain owner; the converter applies it so
a v1 consumer's business code never changes, only its jar version. Cheaper than dual-publish (one
message, no atomicity problem) and the better Tier 2 tool. Rejected with Tier 2. Its real cost, if
revived: `BitsEventHandlerScanner` derives bound routing keys from `@BitsEventHandler` types, so a
consumer handling v1 would need to bind the **v2** queue — the scanner must consult the transform
registry to expand its binding set.

### Bridge/translator service

A deployable consuming v2 and republishing v1 for laggards. Rejected: a new deployable, a new
failure domain, and added latency, to solve a Tier 2 problem.

### Reverse ADR-0004 — adopt canonical Apicurio SerDes

Apicurio's own model stamps `globalId`/`contentId` (v3 defaults to `contentId`) and resolves through
a runtime registry client with a cache; consumers would receive live `DEPRECATED` warnings and could
fetch schemas they do not ship. This is "best practice" as the vendor defines it, and it is the
**only** way to make a wire version identifier actually gate.

Rejected: it reverses a deliberate decision, reintroduces a runtime availability dependency on
Apicurio for message processing, and brings back the cache staleness story ADR-0004 removed. The
better framing — and the one this ADR adopts — is that **the contracts jar *is* the cache**: a
build-time-materialized, immutable, pre-warmed slice of the registry with zero runtime coupling.
ADR-0004 is not "we removed the registry"; it is **compile-time schema resolution**.

## Consequences

- **Positive:** No new mechanism. Tier 1 is what the tooling already does; this ADR mostly names and
  defends it.
- **Positive:** The versioning reasoning is written down again, with ADR-0004 restored beneath it.
- **Positive:** The tolerant reader is promoted from accident to invariant and Phase-1-defended
  (dedicated messaging mapper + generator `additionalProperties` emit/assert + canonicalize).
- **Positive:** The FORWARD choice now records its directional meaning, not just its mechanical one.
- **Negative — the accepted cost of Tier 1:** **schemas grow monotonically.** No field is ever
  removable, because removal is breaking and breaking is unsupported. Left undisciplined, in five
  years `OrderCreated` has 40 fields and a dozen are dead. This is precisely why expand/contract
  discipline is **mandatory** rather than advisory, and why field-level deprecation needs a
  convention despite Draft-07 lacking the keyword.
- **Negative:** A genuinely breaking change has no sanctioned path. That is deliberate — it forces
  escalation and a design conversation rather than a quiet `V2`.
- **Neutral:** Wire `contentHash` and runtime drift telemetry remain deferred (advisory only if
  added — never a reject gate). See [Open work](#open-work).
- **Neutral:** Apicurio version state remains available as a governance signal, correctly
  described as having **no runtime effect** here — `DEPRECATED` does not gate consumers.

## Open work

**Accepted architecture; runtime hardening tracked.** ADR status ≠ “production hardening complete.”

Phase 1 DoD (additive producer bump must not DLQ a consumer with a strict app `ObjectMapper`;
re-register of unchanged schema creates no spurious version) is the bar for calling schema
versioning production-ready — see `spec/schema-versioning-spec.md`.

| Item | Status |
|---|---|
| **QUAL-003 / dedicated messaging `ObjectMapper`** — factory-internal tolerant mapper; app `@Bean ObjectMapper` cannot displace it; isolation E2E (`MessagingObjectMapperIsolationTest`) | **Done** (Phase 1a–1b) |
| **Generator `additionalProperties: true`** — emit on every object node; parse-tree test fails on `false` | **Done** (Phase 1c) |
| **Apicurio `<canonicalize>true`** on contract-module register config | **Done** (Phase 1d) |
| **Wire `contentHash`** — advisory forensic header only; **never gates** consume | **Deferred** (Phase 3; not required for Phase 1 DoD) |
| **Runtime drift telemetry** (hash-miss counter / signal) | **Deferred** — no consumer-side metric yet |
| **`DEPRECATED` / version state at runtime** | **N/A by design** — CI/governance metadata only; runtime never fetches registry content, so deprecation warnings reach nobody. Owners signal via ticket + CODEOWNERS, not the consumer JVM. |

Nothing in this ADR implies that `DEPRECATED`, a wire version, or a `contentHash` miss rejects or
DLQs a message. Consume stays `(group, artifact)` → classpath schema + tolerant reader.
