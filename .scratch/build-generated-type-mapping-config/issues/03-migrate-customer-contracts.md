# 03 — Migrate customer-contracts to generated config

**What to build:** The three customer events cut over to build-time codegen, mirroring the order
migration. `customer-contracts` adds `annotationProcessorPaths` → `schema-gen-tools`;
`CustomerTypeMappingAutoConfiguration` swaps `@RegisterEventMappings("…")` for
`@Import(GeneratedEventTypeMappings.class)`. Customer `TypeMapping` beans now come from generated
`@Bean` methods. With both domains on codegen, the runtime index machinery in `event-contract-kit`
is exercised by nothing but is still present (removed in ticket 04). Bean names, coordinates, routing
keys, exchanges, and app-override-wins stay compatible.

**Blocked by:** 02 — Migrate order-contracts to generated config

**Status:** ready-for-agent

- [ ] `customer-contracts` POM has `annotationProcessorPaths` → `schema-gen-tools`.
- [ ] `CustomerTypeMappingAutoConfiguration` uses `@Import(GeneratedEventTypeMappings.class)`; no
      `@RegisterEventMappings`; `AutoConfiguration.imports` unchanged.
- [ ] `ApplicationContextRunner` context test: all three customer mapping beans exist with correct
      coordinates + routing, and app-override-wins holds.
- [ ] `CustomerEventMappingIndexTest` deleted.
- [ ] Bean names, coordinates, routing keys, exchanges match prior behavior.
- [ ] Both domains build/boot on generated beans; focused suite for both contracts modules passes.
