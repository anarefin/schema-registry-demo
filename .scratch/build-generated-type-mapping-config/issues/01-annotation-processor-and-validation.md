# 01 — Annotation processor + validation in schema-gen-tools

**What to build:** `schema-gen-tools` gains a build-only annotation processor that reads
`@EventMapping` during a contracts module's own `compile` and emits one `@Configuration` per module
of explicit `@Bean TypeMapping` methods — so registration is known at build time with zero runtime
reflection. The processor stays free of a compile dependency on `event-contract-kit` (annotation
matched by FQCN, values read through `AnnotationMirror`). It is registered via
`META-INF/services/javax.annotation.processing.Processor`, targets source version `RELEASE_25`, and
emits into `<eventPackage>.topology.GeneratedEventTypeMappings` with methods sorted by FQCN for
byte-stable output. Each method is `@Bean @ConditionalOnMissingBean(name = "<decapitalizedSimpleName>Mapping")`
returning a `TypeMapping` built only via `Mappings.forDomain(group, exchange).json(type, artifactId,
routingKey)`. The build fails (`Diagnostic.Kind.ERROR`) on blank `groupId`/`exchange`/`routingKey`,
`schemaType != JSON`, and within-module duplicate `(groupId, artifactId)` or bean name. No contracts
module is wired to it yet and the existing runtime index path is untouched, so nothing changes at
runtime.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] `EventMappingProcessor` reads `@EventMapping` by FQCN (no compile dep on `event-contract-kit`),
      registered via `META-INF/services/javax.annotation.processing.Processor`, source `RELEASE_25`.
- [ ] Emits one `GeneratedEventTypeMappings` `@Configuration` per module into `<eventPackage>.topology`,
      one `@Bean @ConditionalOnMissingBean(name=…) TypeMapping` per record, built via `Mappings`, with
      the locked `<decapitalizedSimpleName>Mapping` bean names, methods FQCN-sorted (byte-stable).
- [ ] Constant refs (e.g. `CustomerEventRouting.EXCHANGE`) resolve to their string values.
- [ ] Build fails with `Diagnostic.Kind.ERROR` on blank `groupId`/`exchange`/`routingKey`,
      `schemaType != JSON`, within-module duplicate coordinates or bean names.
- [ ] In-JVM compile unit test asserts generated source content and the ERROR diagnostics
      (blank / non-JSON / duplicate). Decide `ToolProvider.getSystemJavaCompiler()` vs
      `compile-testing` before building.
- [ ] `./mvnw -pl schema-gen-tools test` passes; runtime index path unchanged.
