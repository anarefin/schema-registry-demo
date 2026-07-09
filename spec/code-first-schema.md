# Specification: Code-First Schema Contracts for Orders & Customers

## Purpose

Establish **code-first** as the standard contract workflow for the POC: a Java **record** (with
validation annotations) is the single source of truth, its JSON Schema is **generated** and
committed alongside it, and CI enforces no-drift + compatibility before publication. Each domain —
**orders** and **customers** — gains **three events**, and all six flow end-to-end producer →
registry → consumer.

This supersedes the earlier schema-first (`jsonschema2pojo`) approach and the throwaway
`payment-contracts` pilot (now removed). The design deliberately consolidates the reusable
machinery so that adding a seventh event does **not** re-incur the per-contract "onboarding tax"
catalogued in `docs/CONTRACT-SCALING-STRATEGY.md`.

Companion to `spec/pojo-to-schema.md` (the generic workflow contract).

## Guiding Principles

> **Principles 4 and 5 are superseded** by `spec/contract-owned-amqp-topology.md`, which
> deliberately reverses the transport-agnostic / centralized-topology stance below: each
> `*-contracts` module now owns its own domain-scoped AMQP topology (`spring-rabbit` +
> `spring-boot-autoconfigure` on its classpath), and the reusable topology-building logic lives in
> a new sibling module, `amqp-topology-kit`, not in `schema-messaging-core`. Retained here as
> historical record; see the superseding spec for the current design.

1. Developers author events as **Java Records** with validation annotations; the record is the
   source of truth.
2. JSON Schema is **generated**, committed alongside the record, and never manually edited.
3. Generation is **deterministic** (byte-stable) and lives in exactly one build-only module.
4. ~~The contract jars stay **pure** — no schema-generation dependency (victools) and **no
   Spring/AMQP** on their classpath. A contract is transport-agnostic data + schema.~~
   **Superseded** — see note above.
5. ~~**Reusable plumbing lives in `schema-messaging-core`, not in the contracts.** AMQP topology is a
   transport concern; it is generated once in core and driven by contract metadata, never
   hand-authored per domain.~~ **Superseded** — see note above.
6. CI is the only publisher; every schema passes **drift** + **compatibility** gates first.
7. The published schema is **self-documenting** (per-field descriptions from annotations).

---

## Scope

### In scope
- Establish `order-contracts` and `customer-contracts` as **code-first** (records → generated
  schemas; no `jsonschema2pojo`, no hand-authored schemas).
- Three events per domain, authored as records, with generated schemas committed.
- A single shared build-only generator module (`schema-gen-tools`) — the only home for victools.
- Full end-to-end runtime wiring for all six events, with **topology owned by core** and derived
  from the `TypeMappingRegistry`.
- Governance driven from the **parent POM** (register + `compat-check` + `incompatible-demo`
  inherited, not copy-pasted) with **data-driven** CI + bootstrap.
- Fresh v1 registry baseline (re-seed + re-attach FORWARD rules).
- Documentation: `CONTEXT.md` glossary + an ADR recording the code-first decision.

### Out of scope / deferred
- AsyncAPI / EventCatalog / Architecture Portal doc generation (spec `pojo-to-schema.md` Step 5).
- Promoting the generator to a first-class Maven plugin (Approach B in the scaling strategy). The
  build-only `exec`-driven generator writing into sibling modules is a conscious POC trade-off —
  recorded in ADR-0001.
- Consumer-side deduplication / idempotency (remains downstream responsibility, POC scope).

---

## Domain Model

### Orders (`com.example.contracts.orders`, group `events.orders`)

| Event | Fields (validation) |
| --- | --- |
| `OrderCreated` | orderId, customerId, productId (`@NotNull UUID`); quantity (`@NotNull @Min(1)`); totalAmount (`@NotNull @DecimalMin("0.01", inclusive=false)` BigDecimal); currency (`@NotNull @Size(3,3)`); createdAt (Instant) |
| `OrderShipped` | orderId (`@NotNull UUID`); trackingNumber (`@NotNull @Size`); carrier (`@Pattern` `DHL\|FEDEX\|UPS`); shippedAt (Instant) |
| `OrderCancelled` | orderId (`@NotNull UUID`); reason (`@NotNull`); refundAmount (`@DecimalMin("0.00")` BigDecimal, optional); cancelledAt (Instant) |

### Customers (`com.example.contracts.customers`, group `events.customers`)

| Event | Fields (validation) |
| --- | --- |
| `CustomerRegistered` | customerId (`@NotNull UUID`); email (`@NotNull @Email`); firstName/lastName (`@NotNull @Size`); phoneNumber (`@Pattern` E.164, optional); registeredAt (Instant) |
| `CustomerAddressAdded` | customerId (`@NotNull UUID`); address (`@NotNull @Valid Address`); addedAt (Instant) — **nested object** |
| `CustomerTierChanged` | customerId (`@NotNull UUID`); previousTier (optional); newTier (`@NotNull CustomerTier`); effectiveAt (Instant) — **enum** |

Supporting types: nested record `Address(line1 @NotNull, line2?, city @NotNull, postalCode @NotNull,
countryCode @Size(2,2))`; `enum CustomerTier { BRONZE, SILVER, GOLD }`.

Every record component carries `@JsonPropertyDescription` so the generated schema has per-field
`description` text; the record type carries a type-level description.

---

## Design

### D1 — `schema-gen-tools` (build-only, the sole victools home)

A registered module `schema-gen-tools` (in root `<modules>`) depending on the two contract modules
+ victools; **never** on any service's runtime classpath. Package `com.example.schemagen`
(domain-neutral).

- `SchemaGenerator.generate(Class<?> eventType)` → byte-stable schema string. victools
  `DRAFT_7` (Apicurio 3.2.0's everit compatibility checker only understands up to Draft-07;
  Draft 2020-12 makes the FORWARD merge gate fail with HTTP 500 "could not determine version")
  + `OptionPreset.PLAIN_JSON` + `JacksonModule` +
  `JakartaValidationModule(NOT_NULLABLE_FIELD_IS_REQUIRED, INCLUDE_PATTERN_EXPRESSIONS)`,
  `INLINE_ALL_SCHEMAS`, and **`FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS`** (records expose their
  components as arg-free accessors — this is how properties + annotations are discovered). Stable
  `$id`/`title` from the simple type name; recursive alphabetical key sort; fixed
  `DefaultPrettyPrinter`; trailing newline.
- `GeneratedSchemas.ALL` — the **single source of truth** listing each event type → committed schema
  file (relative to the module dir, writing into the owning contract module's
  `src/main/resources/schemas/`). Adding an event is a one-line change here.
- `SchemaGeneratorCli.main(args)` — no-arg regenerates every target in `GeneratedSchemas.ALL`; also
  accepts explicit `FQCN=outputPath` pairs. Wired to `exec-maven-plugin` at phase `process-classes`
  (no arguments — regenerates all six).
- `SchemaDeterminismTest` (Surefire) iterates `GeneratedSchemas.ALL`: generate twice → byte-identical;
  each committed schema == fresh generation (local mirror of the CI drift gate).

### D2 — Generated artifacts

`order-contracts/src/main/resources/schemas/`: `order-created.schema.json`,
`order-shipped.schema.json`, `order-cancelled.schema.json`.
`customer-contracts/src/main/resources/schemas/`: `customer-registered.schema.json`,
`customer-address-added.schema.json`, `customer-tier-changed.schema.json`.

Delete schema-first residue: the hand-authored `order-created.json` / `customer-registered.json`,
the `*-v1.json` / `*-incompatible.json` test resources, and the `jsonschema2pojo-maven-plugin` block
in both POMs. Keep one adapted `*-incompatible.schema.json` test resource per domain (e.g.
`quantity` integer→string) to drive the rejection demo. Contract POMs end **pure**: `jackson` +
`jakarta.validation-api` compile only.

### D3 — Runtime wiring (all six events; topology owned by core)

> **Superseded** by `spec/contract-owned-amqp-topology.md` D1–D4: topology ownership moved from
> `schema-messaging-core` to each `*-contracts` module (via the new `amqp-topology-kit`), reversing
> the "contracts carry no AMQP/Spring code" stance described below. Retained here as historical
> record of the pre-reversal design.

- **Contracts stay pure — plain routing constants only.** Each `*EventRouting` class holds
  `public static final String` constants (`ROUTING_KEY`, `QUEUE_NAME`, `DLQ_NAME`) per event,
  following the convention `rk` / `rk.queue` / `rk.dlq`
  (`orders.created|shipped|cancelled`, `customers.registered|address-added|tier-changed`). These are
  plain Strings (no Spring dependency), so `@RabbitListener` and the poison demo can reference them
  as compile-time constants.
- **Topology hoisted into `schema-messaging-core` (`…core.amqp`).** The (previously duplicated)
  `EventExchanges` + `RetryTopologyFactory` live here once, plus a single
  `EventTopologyAutoConfiguration` that:
  - declares the 3 shared exchanges **once** (no cross-module `@ConditionalOnMissingBean`
    "first-wins" glue), and
  - **iterates the `TypeMappingRegistry`** and, for each mapping, emits one `Declarables` bean —
    main queue + binding, DLQ + binding, 3-tier TTL retry queues + bindings (`RetryTopologyFactory`)
    — deriving every name from `TypeMapping.routingKey()`. Contracts carry **no** AMQP/Spring code.
  - `contracts → core` is permitted (only `core → contracts` is banned by the enforcer); contracts
    gain no Spring dependency, so the enforcer stays green.
- **TypeMapping:** one bean per event in the producer & consumer `*ContractsConfiguration` classes,
  preserving `schema.{orders,customers}.pinned-version`.
- **Producer:** one REST endpoint per event on `OrderController` / `CustomerController` (map request
  DTO → record → `EventPublisher.publish`), using the record shape (`event.orderId()`). **Drop the
  manual field null-checks** — `SchemaAwareMessageConverter` is the single validation authority
  (throws `SchemaValidationException` → 400). Keep `/api/orders/poison` (adapted).
- **Consumer:** one `@RabbitListener` per queue (typed record parameter), referencing the contracts'
  `QUEUE_NAME` constants.
- **`QueueDepthHealthIndicator`:** inject `TypeMappingRegistry` and iterate it to build the
  queue/DLQ list (derive `*.queue` / `*.dlq` from each routing key) — adding an event needs no edit
  here.

### D4 — Governance (parent-POM driven)

- **Hoist to the parent POM:** the `apicurio-registry-maven-plugin` config and the `compat-check` +
  `incompatible-demo` **profiles** live in the root POM (`pluginManagement` + shared `<profiles>`);
  each contract POM only supplies its three per-artifact coordinates. No copy-pasted profiles.
- `register`: seed all three artifacts per domain (`artifactType=JSON`, `FIND_OR_CREATE_VERSION`).
- `compat-check` profile: dry-run `register` for all generated schemas (merge gate).
- `incompatible-demo` profile: dry-run against each `*-incompatible.schema.json` resource.
- **Fresh v1 reset:** re-seed each artifact from its generated schema and re-attach a **FORWARD**
  compatibility rule via the **data-driven** bootstrap workflow (loop a `group/artifact` list).

### D5 — CI (data-driven)

- `schema-drift-check.yml` (offline / pure-Maven): regenerate via `schema-gen-tools`
  (`process-classes`) then `git diff --exit-code` over both contract modules' `schemas/`. Trigger
  `paths` = `order-contracts/**`, `customer-contracts/**`, `schema-gen-tools/**`.
- `schema-compat-check.yml` / `schema-register.yml`: replace the hardcoded
  `-pl order-contracts,customer-contracts` with a list **derived from the discovered `*-contracts`
  modules** (matrix or computed `-pl`), so new domains need no workflow edit. All payment references
  removed.
- `schema-governance-bootstrap.yml`: loop the `group/artifact` list attaching FORWARD rules instead
  of one hardcoded step per artifact.

### D6 — Documentation

- Root `CONTEXT.md` glossary: Order, Customer, the six events, "contract", "code-first / source of
  truth", "generated schema", registry group/artifact.
- `docs/adr/0001-code-first-schema-generation.md`: record-as-source-of-truth decision; trade-off vs
  schema-first; the v1 reset baseline; the **Approach-B deferral** and **cross-module-write caveat**;
  cross-references `docs/CONTRACT-SCALING-STRATEGY.md`.
- Update `CLAUDE.md` / `README.md`: code-first is the standard for all contract modules; remove the
  payment pilot references; scrub payment from `docs/TESTING-GUIDE.md` and the presentation deck +
  diagrams.

---

## Tasks

### Phase R0 — Remove the payment pilot
- [x] **T-0.1** Delete `payment-contracts/` + `payment-schema-tools/`; drop from root `<modules>`.
- [x] **T-0.2** Delete `spec/tasks.md` + `spec/testing-guide.md` (wholly payment-pilot content).
- [x] **T-0.3** Remove payment from CI workflows + narrative docs (handled within D5 / D6).

### Phase 1 — Generator module
- [x] **T-1.1** Create `schema-gen-tools` module (`com.example.schemagen`); register in root
  `<modules>`; depend on order/customer contracts + victools.
- [x] **T-1.2** `SchemaGenerator.generate(Class)` + `GeneratedSchemas.ALL` + `SchemaGeneratorCli`
  (no-arg regen-all + `FQCN=path`).
- [x] **T-1.3** `schema-gen-tools/pom.xml`; wire `exec-maven-plugin` (`process-classes`, no args).
- [x] **T-1.4** `SchemaDeterminismTest` iterating `GeneratedSchemas.ALL`.

### Phase 2 — Event records
- [x] **T-2.1** Author the three order records (annotated).
- [x] **T-2.2** Author the three customer records + `Address` + `CustomerTier` (annotated).
- [x] **T-2.3** Remove `jsonschema2pojo` + old schemas/test-resources from both POMs; contracts pure.
- [x] **T-2.4** Generate + commit all six `*.schema.json`; verify enrichment (uuid, enum, nested,
  pattern, min/max, `required`, descriptions).

### Phase 3 — Runtime wiring
- [x] **T-3.1** Slim `*EventRouting` to per-event String constants (6 events).
- [x] **T-3.2** Move `EventExchanges` + `RetryTopologyFactory` into `core.amqp`; add
  `EventTopologyAutoConfiguration` iterating `TypeMappingRegistry` (shared exchanges once,
  `Declarables` per mapping). Delete both `*EventTopologyAutoConfiguration` from the contracts.
- [x] **T-3.3** TypeMapping beans (producer + consumer) for all six.
- [x] **T-3.4** Producer endpoint per event; drop manual validation; keep poison demo.
- [x] **T-3.5** Consumer listener per queue; `QueueDepthHealthIndicator` iterates the registry.

### Phase 4 — Governance
- [x] **T-4.1** Hoist apicurio plugin + `compat-check` + `incompatible-demo` into the parent POM;
  contract POMs supply per-artifact coords (3 each).
- [x] **T-4.2** Re-seed v1 + attach FORWARD rules for all six artifacts (data-driven bootstrap).

### Phase 5 — CI, tests, docs
- [x] **T-5.1** Data-driven `-pl` lists in compat/register workflows; drift via `schema-gen-tools`;
  remove payment refs.
- [x] **T-5.2** Rewrite `OrderCreated*Test`, `CustomerRegistered*Test`, `ProducerValidationTest`,
  converter/resolver/`SchemaVersionPinningIT` for the record shape; add value-object tests for the
  new events. Keep the Surefire (`*Test`) / Failsafe (`*IT`) split.
- [x] **T-5.3** `CONTEXT.md`, ADR-0001, `CLAUDE.md`/README updates; scrub payment from remaining
  narrative docs + presentation deck.

---

## Acceptance / Verification

1. `./mvnw clean install` — six schemas generate; determinism test green; enforcer passes
   (`core ↛ contracts` intact); full build passes with payment gone.
2. Generated schema shows: `format:uuid`, `exclusiveMinimum`, `pattern`, `minLength/maxLength`,
   `enum` (tier), nested `address` object, per-field `description`, correct `required`.
3. **Determinism:** build twice → `git status` clean the second time.
4. **Drift gate:** edit a record → committed schema changes; revert only the schema →
   `git diff --exit-code` fails.
5. **Compat gate (registry up):** add an optional field → `-Pcompat-check` passes; change a field
   type → fails with a compatibility rejection.
6. **End-to-end:** `docker compose up`, run the (data-driven) bootstrap to seed + attach FORWARD
   rules, run producer :8081 + consumer :8082, `curl` each of the six endpoints → consumer logs the
   typed event; `/api/orders/poison` → DLQ with `X-Failure-*` headers.
7. `grep -ri payment` over the working tree returns nothing.
