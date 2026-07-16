# 12 — Schema versioning model (compatible evolution only)

**Severity:** Medium · **Findings:** ARCH-010 (versioning model undocumented), DOC-001 (ADR-0004
deleted but cited), QUAL-003 (tolerant reader undefended) · **Type:** Docs

## What to build

Documentation only. **No production code, no POM edits, no test changes, no CI changes.** Four
documents. The code findings below are specified and deferred, not fixed here.

### The problem, restated

The presenting ask was "improve schema versioning at each stage." There is indeed no version
anywhere in the authored → generated → registered → wire pipeline:
[SchemaCoordinates](event-contract-kit/src/main/java/com/example/amqp/topology/mapping/SchemaCoordinates.java)
is `(groupId, artifactId)`;
[@GenerateSchema](event-contract-kit/src/main/java/com/example/amqp/topology/mapping/GenerateSchema.java)
is a bare marker; generated schemas carry `$id: urn:example:schema:<Type>` and no version; every
`<artifact>` in the contracts POMs omits `<version>`, so Apicurio auto-assigns integers nothing in
the repo reads; and only three `X-Schema-*` headers reach the wire (`GroupId`, `ArtifactId`,
`Type`).

**That absence is mostly correct, and the real problem is a different one.** Three facts reframe it:

1. **Minor/additive versioning already works.** Apicurio auto-versions each artifact on content
   change and the FORWARD rule gates it; `ifExists=FIND_OR_CREATE_VERSION` makes registration
   idempotent. "No versioning" is really "**no *major* versioning**."
2. **Major versioning cannot exist under a FORWARD rule, by construction.** A breaking change
   registered as version 2 of artifact `events.orders:OrderCreated` is *rejected by the
   compatibility gate* — correctly. That gate is the thing this POC exists to demonstrate.
   A breaking change is therefore not a version; it would have to be a new artifact.
3. **The reasoning is undocumented because ADR-0004 was deleted.** `docs/adr/` contains only
   ADR-0007 and ADR-0008. ADR-0004 (classpath schemas, no runtime registry) is cited from ~12 live
   source locations and does not exist. Every constraint in the versioning design descends from it.

### Decision: Tier 1 only — compatible evolution

Breaking changes are **explicitly unsupported**: an escalation, not a workflow. Expand/contract
(parallel change) is the sanctioned way to make an apparently-breaking change: add the new field
alongside the old (additive, passes the FORWARD gate, no major version, no migration window),
migrate readers, then remove the old field once provably unread. One hard break becomes three
additive steps the existing machinery already handles with **zero new mechanism**.

| Decision | Outcome |
|---|---|
| Versioning tier | **Tier 1 only** — compatible evolution |
| Breaking-change support | **Rejected** — no `OrderCreatedV2`, no parallel handlers, no coexistence |
| Migration mechanics | Not applicable under Tier 1 — dual-publish / library transform / bridge service all solve a Tier 2 problem |
| Runtime registry | **ADR-0004 stands.** Reframed as *compile-time schema resolution — the contracts jar **is** the cache* |
| Wire identity | Apicurio **`contentHash`** — the only identifier computable at build time. **Advisory; never gates** |
| Deprecation | Apicurio **version state** (`ENABLED`/`DISABLED`/`DEPRECATED`), per-version, as a CI/governance signal |
| Tolerant reader | **Named architectural invariant**, with remediation specified and deferred |

### The forced constraint

In a classpath-resolved architecture the wire version identifier **cannot gate**. A consumer that
meets `hash(v2)` while shipping only v1 cannot fetch (no runtime registry) and cannot fall back to
a schema it does not ship. Rejecting would DLQ the estate on every producer bump. So the
identifier's only possible role is **forensic**, and compatibility rests entirely on two things:
the **FORWARD gate** (CI) and the **tolerant reader** (runtime).

The payoff: a hash **miss is a signal, not a failure** — *"I consumed a message produced from a
schema revision I do not have."* Aggregated, that answers *which revisions are live on the wire*,
which is the evidence needed before flipping a version to `DEPRECATED`.

### Per-stage model

| Stage | Version identity | Mechanism |
|---|---|---|
| **Authoring** | none | Expand/contract discipline; `@Deprecated` field convention |
| **Generation** | none (`$id` stays version-free — determinism is the goal) | Tolerant-reader invariant asserted here |
| **Registry** | auto-assigned integer | FORWARD rule; `FIND_OR_CREATE_VERSION`; `canonicalize` |
| **Wire** | `contentHash` (advisory) | Build-time computable; never gates |
| **Consume** | none — resolution by `(group, artifact)` from classpath | Tolerant reader; hash miss → drift telemetry |
| **Governance** | version state | Drift telemetry → evidence → safe `DEPRECATED` flip |

### Apicurio facts (verified against vendor docs, not memory)

- Version state is **per-version**, not per-artifact:
  `PUT /groups/{g}/artifacts/{a}/versions/{v}/state` with `{"state":"DEPRECATED"}`. States are
  `ENABLED` / `DISABLED` / `DEPRECATED`. (One docs page renders a `SUNSET` state in a state-machine
  diagram; the artifact reference lists only the three — treat `SUNSET` as unverified.)
- `DEPRECATED` signals via **a warning header on the REST response when content is fetched**. This
  runtime never fetches content, so the signal reaches nobody. Version state is governance metadata
  for humans and CI **only** — document this explicitly rather than implying a runtime effect.
- Canonical Apicurio SerDes stamp `globalId` / `contentId` (v3 defaults to `contentId`, changed
  from v2's `globalId`) and resolve through a runtime registry client with a cache — a model
  architecturally incompatible with ADR-0004. Of the three identifiers, only **`contentHash`** is
  derived from content and therefore computable without calling the registry.

### Code findings — recorded, deferred

Each is verified and independent of the versioning design. **None are fixed by this spec.**

1. **QUAL-003 — the tolerant reader holds by accident.**
   [SchemaMessagingAutoConfiguration.java:56-60](schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingAutoConfiguration.java#L56-L60)
   sets `FAIL_ON_UNKNOWN_PROPERTIES=false` on a **`@ConditionalOnMissingBean(ObjectMapper.class)`**
   fallback. Any application defining its own `ObjectMapper` displaces it; Jackson's own default is
   `true`; that service would DLQ every forward-compatible message it received. The tests cannot
   catch this — every test mapper sets the flag **by hand**
   ([JsonSchemaStrategyTest.java:53](schema-messaging-core/src/test/java/com/example/messaging/core/serde/JsonSchemaStrategyTest.java#L53),
   and five more sites), so they exercise a mapper production may not have.
2. **`additionalProperties` is absent by accident** — a consequence of `OptionPreset.PLAIN_JSON` in
   [SchemaGenerator.java](schema-gen-tools/src/main/java/com/example/schemagen/SchemaGenerator.java).
   JSON Schema defaults it to `true`, which is precisely what lets an old consumer read new bytes.
   A future "tighten the schemas" change adding `additionalProperties: false` would silently turn
   every forward-compatible message into a DLQ'd `SchemaValidationException`.
3. **DOC-001 — the decision log was deleted, and it is recoverable.** Commit `c0f9738`
   ("feat(schema-registry): update documentation for new OrderFulfilled event and schema
   integration", 2026-07-12) deleted **all eight** ADRs — 583 lines — as apparent collateral in a
   docs sweep; nothing in the commit message suggests the log was meant to be discarded. Live Java
   still cites them: **ADR-0004 ×6, ADR-0005 ×2, ADR-0006 ×2, ADR-0003 ×2**. Every one is
   recoverable verbatim via `git show c0f9738^:docs/adr/<file>` — **restoration beats
   reconstruction, and beats editing the citations.** ADR-0004 is restored by this spec; 0003,
   0005 and 0006 remain deleted and cited.

   **Number-reuse hazard:** the current `ADR-0007-domain-topology-mapping-factories` and
   `ADR-0008-publisher-owned-messaging-topology` **reuse the numbers** of two unrelated deleted
   ADRs (`0007-typemapping-carries-exchange`,
   `0008-bitsevenhandler-programmatic-listener-registration`). "ADR-0007" is ambiguous across eras.
   No Java cites either number today, so the hazard is latent — but restoring the old 0007/0008
   under their original names would collide, and any restoration of those two needs renumbering
   first.
4. **`GeneratedSchemas.ALL` does not exist** — `.github/workflows/schema-governance-bootstrap.yml`
   instructs maintainers to keep its hardcoded `ARTIFACTS` list in sync with a deleted class.
5. **`<canonicalize>` unset** — `FIND_OR_CREATE_VERSION` therefore matches on **raw bytes**, so
   version-bump idempotency rests on the generator's byte-determinism rather than on the registry's
   content comparison. Load-bearing but incidental.
6. **Draft-07 has no `deprecated` keyword** (added in 2019-09), and the draft is pinned because
   Apicurio 3.2.0's everit checker HTTP-500s on Draft 2020-12
   ([SchemaGenerator javadoc](schema-gen-tools/src/main/java/com/example/schemagen/SchemaGenerator.java)).
   Field deprecation must therefore be a convention, not a keyword.
7. **`CLAUDE.md` is stale** — describes "Schema Version Pinning (spec §10.5)" and
   `StartupSchemaValidator`; neither exists.

## Acceptance criteria

- [x] `docs/adr/0004-local-schema-validation.md` — **restored verbatim from git**
      (`git show c0f9738^:...`), not reconstructed. Kept at its **original path** so all six live
      citations resolve with **zero source edits** — deliberately not renamed to the newer
      `ADR-000N-` convention, since renaming would leave them dangling. Carries a restoration note
      recording provenance, the number-reuse hazard, and one known text drift ("six schemas" — there
      are now seven; `OrderFulfilled` was added by the very commit that deleted the ADR). Body
      unmodified.

      The restored text already contains the decision this whole spec re-derives: *"there is no
      'version' concept left once there is exactly one schema per build"* and *"Lost capability:
      runtime schema version pinning."*
- [ ] New `docs/adr/ADR-0009-schema-versioning-model.md` — Tier 1 decision; the per-stage table; the
      forced constraint and why the wire identifier cannot gate; the FORWARD **directional** bet
      (producer-first, consumers lag — today only the mechanical `NARROWED` reason is recorded); the
      tolerant reader as a named invariant with its two props, the `@ConditionalOnMissingBean` risk,
      and the end-to-end test that must defend it; rejected alternatives (Tier 2 new-artifact major
      versions + parallel handlers, dual-publish, library-owned transform, bridge service, version as
      a third `SchemaCoordinates` field, reversing ADR-0004 for canonical Apicurio SerDes) each with
      its reason; consequences (schemas grow monotonically, no field is ever removable — the accepted
      cost of Tier 1 and the reason expand/contract discipline is mandatory).
- [ ] `CONTEXT.md` glossary gains *compatible evolution*, *expand/contract (parallel change)*,
      *tolerant reader*, *contentHash*, *version state*, *schema drift*, and *FORWARD* (directional
      meaning), in the existing table style.
- [ ] Every code claim in both ADRs cites a real `file:line`; every surviving `ADR-000[1-6]` citation
      in source resolves to a file that now exists, or this spec records why it does not.
- [ ] `./mvnw -pl order-contracts,customer-contracts -am process-classes` then
      `git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'` — confirms the docs session
      touched no schema.

## Deferred to a later coding session (explicitly not this spec)

- [ ] Defend the tolerant reader: an end-to-end test through the **real auto-configured**
      `ObjectMapper` — old consumer schema, new payload carrying an extra field, asserting successful
      consumption. Consider whether core should own the mapper's deserialization posture rather than
      yielding it to `@ConditionalOnMissingBean`.
- [ ] Assert the `additionalProperties` invariant in the generator's test suite.
- [ ] Add `<canonicalize>true</canonicalize>` so registration idempotency is content-based rather
      than byte-based.
- [ ] Restore ADR-0003, ADR-0005, ADR-0006 the same way ADR-0004 was — `git show c0f9738^:docs/adr/<file>`,
      at their original paths, so their six remaining citations resolve without touching source.
      **Review each for drift before restoring**: unlike ADR-0004 they may describe decisions since
      superseded (e.g. the deleted `0002-contract-owned-amqp-topology` is reversed by the current
      ADR-0008), so a restored-but-stale ADR could mislead more than a missing one. Annotate as
      ADR-0004 was.
- [ ] Decide the ADR-0007/0008 number-reuse hazard: either renumber the current pair, or record
      explicitly that ADR numbers below 0007 refer to the pre-`c0f9738` era.
- [ ] Fix or remove the `GeneratedSchemas.ALL` instruction in the bootstrap workflow.
- [ ] Correct the stale "Schema Version Pinning" / `StartupSchemaValidator` section in `CLAUDE.md`.
- [ ] Implement the `contentHash` wire header and the drift metric, if and when the drift telemetry
      is actually wanted for deprecation governance.

## Blocked by

None — can start immediately. Write order: this spec → ADR-0004 (the foundation ADR-0009 cites) →
ADR-0009 → `CONTEXT.md`.
