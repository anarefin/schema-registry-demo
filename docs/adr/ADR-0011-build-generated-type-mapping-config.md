# ADR-0011: Build-generated `TypeMapping` config via an annotation processor

**Status:** Accepted  
**Date:** 2026-07-20  
**Spec:** `spec/15-build-generated-type-mapping-config.md`  
**Supersedes (in part):** [ADR-0010](ADR-0010-annotation-driven-event-mappings.md) — the runtime
index-driven registration (`@RegisterEventMappings` + `EventMappingRegistrar` +
`IndexedEventMappings` + `EventMappingIndexReader`/`Writer` + `META-INF/event-mappings.idx`)  
**Retains from ADR-0010:** `@EventMapping` on the record as the single source of schema identity +
AMQP route; the locked `Introspector.decapitalize(simpleName) + "Mapping"` bean names;
`@ConditionalOnMissingBean(name=…)` app-override-wins; `Mappings` as the sole `TypeMapping`
construction path; mapping registration never declares an exchange  
**Does not supersede:** ADR-0007's `DomainTopology` / `Mappings` factories;
[ADR-0008](ADR-0008-publisher-owned-messaging-topology.md) exchange ownership; ADR-0009 versioning

## Context

ADR-0010 moved mapping registration off hand-written `@Bean` methods onto a **runtime** path: each
`*TypeMappingAutoConfiguration` was meta-annotated `@RegisterEventMappings("pkg")`, which imported
`EventMappingRegistrar` (an `ImportBeanDefinitionRegistrar`). At context refresh the registrar read
the build-time `META-INF/event-mappings.idx`, `Class.forName`-ed every FQCN, read `@EventMapping`
reflectively, built a `TypeMapping` via `Mappings`, and registered one bean per record.

That works but pays for it at runtime: reflection, a classpath resource merge, `Class.forName`
across all domains, and per-loader index loading (the reason the now-obsolete Spec 14 memoization
existed). Yet the mapping data is fully known at **build time** — nothing about it needs to be
rediscovered when the app boots.

## Decision

Move registration to **build time**. An annotation processor (APT) in `schema-gen-tools` reads
`@EventMapping` during each contracts module's own `compile` and emits an explicit
`@Configuration` of `@Bean TypeMapping` methods, which javac compiles in the same build. At runtime
there is **no reflection and no index read** — just plain Spring `@Bean` methods.

### The processor

`EventMappingProcessor` (`schema-gen-tools`, `extends AbstractProcessor`) is build-only — never on
a runtime classpath — and keeps **no** compile dependency on `event-contract-kit`: the annotation
is matched by FQCN (`com.example.amqp.topology.mapping.EventMapping`) and its attribute values are
read through `AnnotationMirror`, never a typed annotation instance. It is registered via
`META-INF/services/javax.annotation.processing.Processor` and targets `RELEASE_25`.

- Reads each annotated `TypeElement`'s `@EventMapping` values (constant refs like
  `OrderEventRouting.EXCHANGE` are resolved to their string values by javac).
- Emits **one** `@Configuration` per module via `Filer` into `<eventPackage>.topology`
  (orders → `com.example.contracts.orders.topology.GeneratedEventTypeMappings`), methods sorted by
  event FQCN and carrying no timestamp — **byte-stable** output.
- Each method is `@Bean @ConditionalOnMissingBean(name = "<decapitalizedSimpleName>Mapping")`
  returning a `TypeMapping` built **only** via
  `Mappings.forDomain(group, exchange).json(type, artifactId, routingKey)`.

```java
@Bean
@ConditionalOnMissingBean(name = "orderCreatedMapping")
public TypeMapping orderCreatedMapping() {
    return Mappings.forDomain("events.orders", "events.orders.exchange")
            .json(com.example.contracts.orders.OrderCreated.class,
                  "OrderCreated", "orders.created");
}
```

### Build-time validation (fail-fast)

The processor fails the build via `Diagnostic.Kind.ERROR` — emitting nothing — on a blank
`groupId`/`exchange`/`routingKey`, a `schemaType` other than `JSON`, or a **within-module**
duplicate `(groupId, artifactId)` or bean name. JSON Schema generation (`@GenerateSchema` +
`schema-gen-tools` at `process-classes`) and its `EventMappingValidator` fail-fast are unchanged;
only `.idx` writing is removed. (Processor at `compile` and validator at `process-classes` overlap
on annotation checks — redundant but both fail-fast, acceptable.)

### Registration wiring

The hand-written `@AutoConfiguration` markers stay (still listed in each module's
`AutoConfiguration.imports`, untouched); `@RegisterEventMappings("…")` is swapped for
`@Import(GeneratedEventTypeMappings.class)`. A same-compilation forward reference to a generated
type is the standard MapStruct/Dagger pattern — javac's multi-round processing compiles the
generated class first; works in Maven and in IDEs with annotation processing enabled.

### Build wiring

Each contracts module adds `annotationProcessorPaths` → `schema-gen-tools` on
`maven-compiler-plugin`, so the processor runs at that module's own `compile`. The processor uses
only JDK APT + `java.*`; `schema-gen-tools`' victools deps ride the processor path but are unused
by codegen and isolated from the compile classpath.

## Accepted trade-off: the cross-jar bean-name-collision guard is gone

ADR-0010's `IndexedEventMappings` validated bean-name collisions **across jars** at startup: if two
domains contributed a record with the same Java simple name (both `StatusChanged` →
`statusChangedMapping`), startup aborted with a deterministic error.

That guard is **removed**. Bean names are now assigned per-module by independent
`GeneratedEventTypeMappings` configs, each guarding only its own beans with
`@ConditionalOnMissingBean(name=…)`. Two domains sharing a Java simple name would now collide on
that bean name: the second config's `@Bean` is **silently skipped**, one mapping is dropped, and
the failure surfaces as a **runtime lookup miss** (`findByJavaType` / `findByCoordinates` returns
empty) rather than a startup abort.

What still catches cross-jar mistakes: `TypeMappingRegistry` throws at construction on cross-jar
duplicate **coordinates** and duplicate **javaType**. The gap is narrow — it is only the specific
case of two *distinct* event types, in two *different* domains, sharing a Java simple name (hence a
bean name) while carrying *different* coordinates and javaTypes, so neither registry guard fires.
For this two-domain POC (orders + customers, disjoint simple names) the case does not arise; the
mitigation if it ever does is the same discipline the codebase already relies on — keep event
record simple names unique across domains.

## Rejected alternatives

### Keep the runtime index (ADR-0010 as-is)

Pays reflection + `Class.forName` + per-loader index merge at every boot for data known at build
time, and needed a memoization layer (Spec 14) just to not repeat that work per domain. The
codegen path deletes both the cost and the memoization.

### Runtime classpath package scan

Already rejected in ADR-0010 for the same reasons (non-deterministic across jars, blurs the
build/runtime boundary) — unchanged here.

### Keep a build-time index but stop reflecting at runtime

Half-step: still writes and ships `META-INF/event-mappings.idx` as a second build artifact with no
consumer. If registration is compiled, the index has no reader — remove it.

## Consequences

- **Positive:** Zero runtime reflection and no `.idx` read for mapping registration — plain `@Bean`
  methods javac already compiled. Spec 14 (per-loader index memoization) is obsolete and deleted.
- **Positive:** Registration errors (blank/non-JSON/within-module duplicate) fail the **module
  compile**, earlier than the old startup abort, before a broken jar ships.
- **Positive:** New-event onboarding is unchanged for authors — annotate the record + build +
  commit the generated schema; **no** mapping `@Bean` (the codegen writes it).
- **Neutral:** `schema-messaging-core` unchanged for mapping discovery (still injects
  `List<TypeMapping>`); `@EventMapping` semantics, bean names, coordinates, routing, exchanges, and
  app-override-wins are all preserved from ADR-0010.
- **Negative (accepted):** The cross-jar bean-name-collision guard is gone (see above) — a same
  simple name in two domains silently drops a mapping instead of aborting startup.
- **Failure modes (compile):** blank `groupId`/`exchange`/`routingKey`, `schemaType != JSON`,
  within-module duplicate coordinates or bean names.
- **Failure modes (startup):** cross-jar duplicate coordinates or duplicate javaType
  (`TypeMappingRegistry`); missing/malformed classpath schema (`LocalSchemaCatalog`).
