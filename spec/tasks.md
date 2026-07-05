# Tasks: Code-First POJO→Schema Workflow (parallel demo module)

Execution checklist for `spec/pojo-to-schema.md`. Adds a **new parallel module**
`payment-contracts` that demonstrates the code-first workflow (Java Record → generated
JSON Schema) alongside the existing schema-first modules, which stay untouched.

**Mechanism:** victools/jsonschema-generator run via `exec-maven-plugin` at `process-classes`.
**Scope:** generation + CI drift gate + CI compat gate. Docs (AsyncAPI/EventCatalog) out of scope.

---

## Phase 1 — Module scaffold & versions

- [ ] **T-1.1** Create module dir `payment-contracts/` and add `<module>` to root `pom.xml`.
- [ ] **T-1.2** In root `pom.xml` `<properties>` + `<dependencyManagement>`, pin (versions in parent only):
  - [ ] `com.github.victools:jsonschema-generator` (+ `-module-jackson`, `-module-jakarta-validation`), latest 4.x — verify exact version at implement time
  - [ ] `jakarta.validation:jakarta.validation-api`
  - [ ] `org.codehaus.mojo:exec-maven-plugin`
- [ ] **T-1.3** Create `payment-contracts/pom.xml` inheriting parent; deps: Jackson (inherited),
  victools (build/runtime for generator), validation-api (compile). No version re-declaration.
  - *Done-check:* `./mvnw -pl payment-contracts validate` succeeds.

## Phase 2 — Author the event model

- [ ] **T-2.1** Create `PaymentProcessedEvent.java` (record) under
  `com.example.contracts.payments.codefirst` with annotations: `@NotNull paymentId (UUID)`,
  `@NotNull orderId (UUID)` (cross-domain reference to OrderCreated),
  `@NotNull @DecimalMin("0.01") amount (BigDecimal)`,
  `@NotNull @Size(min=3,max=3) currency (String)` (ISO 4217, e.g. `GBP`),
  `@Pattern(regexp="CARD|BANK_TRANSFER|WALLET") paymentMethod`,
  `Instant processedAt`.
  - *Done-check:* compiles; annotations resolve against validation-api.

## Phase 3 — Schema generator (determinism is the whole game)

- [ ] **T-3.1** Create `SchemaGenerator.java` (main): victools `SchemaGeneratorConfigBuilder` with
  `JacksonModule` + `JakartaValidationModule`, `DRAFT_2020_12`, records enabled
  (`FIELDS_DERIVED_FROM_ARGUMENTS_AND_TYPE`), inline definitions.
- [ ] **T-3.2** Enforce byte-stable output: serialize the tree through an `ObjectMapper` with
  `ORDER_MAP_ENTRIES_BY_KEYS` + `SORT_PROPERTIES_ALPHABETICALLY`, fixed `DefaultPrettyPrinter`,
  trailing newline; stable `$id`/`title` from type name (no timestamps/env values).
- [ ] **T-3.3** `main(args)` takes output path; writes
  `src/main/resources/schemas/payment-processed.schema.json`.
- [ ] **T-3.4** Wire `exec-maven-plugin` `java` goal to phase **`process-classes`**, `mainClass` =
  `...codefirst.SchemaGenerator`, arg = output path.
  - *Done-check:* `./mvnw -pl payment-contracts process-classes` writes the schema file.

## Phase 4 — Generated artifact & determinism guard

- [ ] **T-4.1** Run the build; commit the generated `payment-processed.schema.json`.
- [ ] **T-4.2** Verify enrichment: schema has `exclusiveMinimum` (from `@DecimalMin`), `pattern`
  (from `@Pattern` on `paymentMethod`), `minLength`/`maxLength` (from `@Size` on `currency`),
  and a correct `required` array for `paymentId`, `orderId`, `amount`, `currency`.
- [ ] **T-4.3** Create `SchemaDeterminismTest.java` (Surefire, `*Test.java`): generates twice
  in-memory → byte-identical; committed file == fresh generation (local mirror of CI drift gate).
  - *Done-check:* `./mvnw -pl payment-contracts test` passes; second build leaves
    `git status` clean.

## Phase 5 — Compatibility gate (POM profile)

- [ ] **T-5.1** Copy `customer-contracts`' `compat-check` profile (`customer-contracts/pom.xml:118`)
  into the new module: apicurio-registry-maven-plugin `register` `dryRun=true`, group
  `events.payments`, **artifactId `PaymentProcessed`** (distinct from `OrderCreated` and `CustomerRegistered`),
  `artifactType=JSON`, file = the generated schema.
- [ ] **T-5.2** Seed an initial `PaymentProcessed` version + attach a **FORWARD** rule (bootstrap path /
  README §2 mechanism) so the gate is meaningful on the second change.
  - *Done-check (registry up):* `./mvnw -pl payment-contracts verify -Pcompat-check
    -Dapicurio.registry.url=http://localhost:8080` passes for an optional-field add, fails for a
    required-field add.

## Phase 6 — CI gates

- [ ] **T-6.1** Create `.github/workflows/schema-drift-check.yml` (offline, `ubuntu-latest`):
  checkout → JDK 25 → `./mvnw -pl payment-contracts process-classes` →
  `git diff --exit-code -- payment-contracts/src/main/resources/schemas/`. Trigger on
  `payment-contracts/**`.
- [ ] **T-6.2** Extend `.github/workflows/schema-compat-check.yml`: add new module to
  `on.pull_request.paths` and to the `-pl` list (runs on existing self-hosted `apicurio-local`).

## Phase 7 — Docs

- [ ] **T-7.1** Add a short README/`docs/` section contrasting schema-first vs code-first (the demo
  narrative). Note Approach B (real Maven plugin) as the productionization path.

---

## End-to-end verification

1. `./mvnw -pl payment-contracts clean install` — generates schema, determinism test green.
2. Inspect generated schema for annotation-driven enrichment.
3. **Drift demo:** edit record → schema diff appears; revert schema only → `git diff --exit-code` fails.
4. **Determinism:** build twice → `git status` clean second time.
5. **Compat demo:** optional field passes; required field fails with a compatibility rejection.

## Out of scope / deferred

- AsyncAPI + EventCatalog + Architecture Portal doc generation (spec Step 5).
- Promoting the generator to a real Maven plugin (Approach B).
- Wiring `PaymentProcessedEvent` into the producer/consumer runtime topology.
