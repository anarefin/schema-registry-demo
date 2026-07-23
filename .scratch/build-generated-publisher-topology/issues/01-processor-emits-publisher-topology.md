# 01 — Processor emits generated `*PublisherTopology` + domain validation

**What to build:** `EventMappingProcessor` gains a second, opt-in generation pass. Alongside the
existing `GeneratedEventTypeMappings`, the same validated pass emits one publisher-topology source
per contracts module into `<eventPackage>.topology` — `CustomersPublisherTopology` for
`com.example.contracts.customers`, `OrdersPublisherTopology` for `com.example.contracts.orders`.
The generated class is plain `@Configuration` (never `@AutoConfiguration`), holds a
`DomainTopology.of("<exchange>")` constant and three `@Bean @ConditionalOnMissingBean(name=…)`
`TopicExchange` methods (`<beanPrefix>Exchange` / `<beanPrefix>Dlx` / `<beanPrefix>RetryExchange`),
delegating all exchange and DLX/retry naming to `DomainTopology` — the processor duplicates no
`TopologyNaming` rules. After per-event validation, the pass collects a single immutable
`DomainDescriptor(groupId, exchange, beanPrefix, publisherTopologySimpleName)`: `beanPrefix` is the
final `groupId` segment (`events.customers` → `customers`); the class name is
`capitalize(lastEventPackageSegment) + "PublisherTopology"` (no singularization). Both sources go
through `Filer#createSourceFile` using every annotated event type as an originating element, with
byte-stable output (no timestamp; deterministic package/class/method/import order). Nothing imports
the new class yet — hand-written `*PublisherTopology` classes stay in place, generated names differ
(`Orders` vs `Order`), so there is no bean collision and no runtime change. `schema-gen-tools` gains
no compile dependency on Spring, contracts, core, or `event-contract-kit` — generated types are
referenced by FQCN strings.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

> Part of a single change set (see `spec/16`). Tickets 01→02→03 must all merge together; this ticket
> is written so the build stays green on its own (generated class compiles, nothing imports it).

- [ ] Extend the in-JVM compiler-test stubs with `DomainExchanges`, `DomainTopology`,
      `TopicExchange`, and `Configuration`.
- [ ] Happy-path test asserts a generated `demo.events.topology.EventsPublisherTopology` with the
      three exact bean names and `DomainTopology.of("events.demo.exchange")`, and that
      `AutoConfiguration.imports` contains **only** `demo.events.topology.GeneratedEventTypeMappings`.
- [ ] Failure tests: mixed `groupId`, mixed `exchange`, and invalid derived Java identifier (final
      `groupId` segment or final event-package segment) all fail compilation with a
      `Diagnostic.Kind.ERROR` naming the conflicting event types and values, and **no** source and
      **no** `AutoConfiguration.imports` are emitted (validate fully before the first `Filer` write).
- [ ] `EventMappingProcessor.generate()` refactored into validation + two render/write paths
      (`writeSource(mappingFqcn, renderMappings(...))`, `writeSource(topologyFqcn,
      renderPublisherTopology(...))`, `writeImports(mappingFqcn)`); all-or-nothing behavior preserved.
- [ ] Generated topology is plain `@Configuration`; `writeImports(...)` still receives only
      `GeneratedEventTypeMappings`; no second Spring imports resource is created.
- [ ] `schema-gen-tools` gains no compile dep on Spring, contracts, core, or `event-contract-kit`.
- [ ] `./gradlew :schema-gen-tools:test --tests EventMappingProcessorTest` passes (new tests fail
      before implementation, pass after).
