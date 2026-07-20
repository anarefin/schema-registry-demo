# 02 — Migrate order-contracts to generated config (first domain E2E)

**What to build:** Order domain is the first end-to-end cutover to build-time codegen. `order-contracts`
adds `annotationProcessorPaths` → `schema-gen-tools` on `maven-compiler-plugin`, so the processor
runs at the module's own `compile` and emits `GeneratedEventTypeMappings`. `OrderTypeMappingAutoConfiguration`
swaps its `@RegisterEventMappings("…")` meta-annotation for `@Import(GeneratedEventTypeMappings.class)`;
the hand-written `@AutoConfiguration` marker and the `AutoConfiguration.imports` resource are
untouched. Order `TypeMapping` beans now come from generated `@Bean` methods (zero reflection, no
`.idx` read) while customer beans still come from the runtime index path — both coexist and the
producer/consumer boot exactly as before. Bean names, coordinates, routing keys, exchanges, and
app-override-wins all stay compatible; mapping registration still declares no exchange.

**Blocked by:** 01 — Annotation processor + validation in schema-gen-tools

**Status:** ready-for-agent

- [ ] `order-contracts` POM has `annotationProcessorPaths` → `schema-gen-tools`; processor runs at
      the module's own `compile`.
- [ ] `OrderTypeMappingAutoConfiguration` uses `@Import(GeneratedEventTypeMappings.class)`; no
      `@RegisterEventMappings`; `AutoConfiguration.imports` unchanged.
- [ ] `ApplicationContextRunner` context test: all four order mapping beans exist with correct
      coordinates + routing, and an application `@Bean` of the same name wins (app-override-wins).
- [ ] `OrderEventMappingIndexTest` deleted.
- [ ] Bean names, coordinates, routing keys, exchanges match prior behavior; publisher topology stays
      opt-in; no exchange declared by mapping registration.
- [ ] Focused order-contracts (+ deps) tests pass; customer domain still green on the runtime path.
