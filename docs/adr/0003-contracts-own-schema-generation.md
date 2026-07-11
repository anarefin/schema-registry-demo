# ADR-0003: Contracts Own Their Own Schema Generation

## Status

Accepted. Supersedes the module-dependency-direction and invocation-binding details of
[ADR-0001](0001-code-first-schema-generation.md) / `spec/code-first-schema.md` D1/D2 (the victools
config and deterministic-output details there are unaffected).

## Context

[ADR-0001](0001-code-first-schema-generation.md) made `schema-gen-tools` depend ON
`order-contracts` and `customer-contracts`, so it could read their annotated records, with its
`exec-maven-plugin` execution bound to its *own* `process-classes` phase. Generation then wrote
into the sibling contracts modules using hardcoded `../order-contracts/...` relative paths (via a
`schemagen.basedir` system-property workaround) and a hardcoded `GeneratedSchemas.ALL` list of
exactly six `(Class<?>, path)` pairs.

This inverted the ownership a code-first design implies: a contracts module could not generate its
own schema as part of its own build — generation only happened when `schema-gen-tools` itself was
built, reaching across the reactor into its dependencies' source trees. Wiring generation directly
into each contracts module's own `process-classes` naively (contracts depend on schema-gen-tools,
which still depends back on contracts) would create a Maven reactor cycle.

## Decision

- **Invert the dependency.** `schema-gen-tools` has zero dependency, compile or test scope, on
  either contracts module. It stays a plain jar (not a custom Maven plugin/Mojo — that's ADR-0001's
  already-deferred "Approach B," not picked up here).
- **Each contracts module invokes it from its own build.** `order-contracts` and
  `customer-contracts` each declare `schema-gen-tools` as a plugin-level `exec-maven-plugin`
  dependency (not a project dependency) and bind the `java` goal to their own `process-classes`
  phase. Maven's reactor sort already accounts for plugin-level dependencies, so `schema-gen-tools`
  still builds first — no cycle.
- **Package scan via `@GenerateSchema` (amends the original “explicit FQCN args” bullet).**
  Each contracts module passes `SchemaGeneratorCli` three arguments:
  `<schemasOutputDir> <classesDir> <basePackage>`. The CLI walks
  `${project.build.outputDirectory}` under that package and generates schemas for every type
  annotated with `@GenerateSchema` (`com.example.amqp.topology.mapping.GenerateSchema` in
  `event-contract-kit`). Nested value objects / enums / topology auto-configs stay unmarked.
  Zero hits fails the build. Ownership model (contracts invoke gen at their own
  `process-classes`; `schema-gen-tools` stays free of contracts deps) is unchanged.
- ~~**Explicit args, not classpath scanning.** Each module passes `SchemaGeneratorCli`'s existing
  `FQCN=outputPath` arguments explicitly (three per module, built from `${project.basedir}`) rather
  than having the tool discover event classes via package scanning/reflection. Smallest diff, no
  new scanning dependency or "what counts as an event class" convention to invent.~~
  **Superseded** by the `@GenerateSchema` package-scan amendment above.
- **Determinism tests move with the classes they test.** The `SchemaDeterminismTest` that lived in
  `schema-gen-tools` (iterating `GeneratedSchemas.ALL`) could not stay — a test-scope dependency on
  the contracts modules would recreate the very cycle being removed. Each contracts module now has
  its own determinism test, calling the still-generic `SchemaGenerator.generate(Class<?>)` directly
  against its own three classes.
- Scope is deliberately narrow: this fixes the dependency direction and hardcoded list for the two
  existing contracts modules. It does not pursue the larger manifest-driven, N-contract redesign
  sketched in the deleted `docs/CONTRACT-SCALING-STRATEGY.md` (recoverable via
  `git show 8f8bd60c~1:docs/CONTRACT-SCALING-STRATEGY.md`), which remains a future option, not a
  commitment made here.

## Consequences

- A contracts module is now a complete, self-sufficient schema-generation unit: running
  `./mvnw -pl order-contracts process-classes` regenerates only that domain's schemas, from its own
  classes, with no reach into a sibling module's source tree.
- Adding a seventh event to an existing domain means adding one `@GenerateSchema`-annotated record
  (plus TypeMapping / Apicurio wiring) — not editing a shared FQCN list in the POM.
- Adding a third contracts module still means hand-wiring its own `exec-maven-plugin` execution
  (copy the pattern, point `basePackage` at the new domain) — this ADR does not make onboarding a
  new *domain* one line; only regeneration ownership for existing domains moved.
- CI (`schema-drift-check.yml`) and `CLAUDE.md`'s documented drift command now target
  `order-contracts,customer-contracts` directly instead of `schema-gen-tools`.
