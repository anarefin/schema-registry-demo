# 04 — Migrate order-contracts (first domain E2E)

**What to build:** Order domain is the first end-to-end cutover. All four order event records carry
`@GenerateSchema` + `@EventMapping` (reuse `OrderEventRouting` constants). Manual per-event
`TypeMapping` `@Bean` methods disappear; `OrderTypeMappingAutoConfiguration` becomes a thin
`@Import` of the package-scoped registrar for `com.example.contracts.orders`. Packaged jar
contains the build-output index. Bean names, coordinates, routing keys, exchanges, overrides, and
`TypeMappingSelection` stay compatible. Publisher topology remains opt-in; mapping registration
declares no exchanges.

**Blocked by:** 02 — Build-time index + paired-annotation validation; 03 — Index reader + mapping registrar

**Status:** done

- [x] Four order records annotated; no manual per-event `TypeMapping` bean methods remain.
- [x] Auto-config imports the indexed registrar scoped to the exact orders event package.
- [x] Packaged `order-contracts` jar contains `META-INF/event-mappings.idx` with the four FQCNs.
- [x] Expected coordinates, schema types, routing keys, exchanges, and bean names match prior behavior.
- [x] App bean override and `TypeMappingSelection` still work; publisher topology stays opt-in.
- [x] Schema regeneration creates no unexpected schema diff; focused order-contracts (+ deps) tests pass.
