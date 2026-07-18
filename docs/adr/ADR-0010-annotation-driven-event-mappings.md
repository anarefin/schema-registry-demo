# ADR-0010: Annotation-driven event mappings with a build-generated index

**Status:** Accepted  
**Date:** 2026-07-18  
**Spec:** `spec/13-annotation-driven-event-mappings.md`  
**Supersedes (in part):** [ADR-0007](ADR-0007-domain-topology-mapping-factories.md) — the manual
per-event `TypeMapping` `@Bean` methods in each `*TypeMappingAutoConfiguration`  
**Does not supersede:** ADR-0007's `DomainTopology` / `Mappings` factories; named override
semantics; [ADR-0008](ADR-0008-publisher-owned-messaging-topology.md) exchange ownership

## Context

ADR-0007 reduced each contracts module's mapping wiring to one-line `@Bean` delegations through
`Mappings.forDomain(...).json(...)`. That removed copy-paste inside each method body, but every
new event still needed a hand-written `@Bean("…Mapping")` + `@ConditionalOnMissingBean` method —
schema identity and AMQP route lived next to the record in spirit, yet were authored twice (record
fields in one file; mapping coordinates in auto-config).

Schema generation already had a build-time discovery path (`@GenerateSchema` + `schema-gen-tools`
at `process-classes`). Mapping registration did not: it stayed a Spring `@Configuration` list that
drifted from the event set whenever someone forgot a bean.

Runtime classpath package scanning was rejected: it would couple startup to classpath shape, make
failures non-deterministic across jars, and blur the build-time / runtime boundary that
`LocalSchemaCatalog` already enforces for schemas.

## Decision

### Declarative metadata on the event record

Each code-first event record carries both annotations:

```java
@GenerateSchema
@EventMapping(
        groupId = "events.orders",
        exchange = OrderEventRouting.EXCHANGE,
        routingKey = OrderEventRouting.CREATED_ROUTING_KEY)
public record OrderCreated(...) {}
```

`@EventMapping` (in `event-contract-kit`) holds schema identity and AMQP route only. Empty
`artifactId` means `javaType.getSimpleName()`. Reuse `*EventRouting` constants for exchange and
routing key — do not derive them from class names. The annotation never declares an exchange and
carries no Spring or queue/DLQ/retry fields.

### Build-time index (not committed)

During the existing contracts-module `process-classes` `exec-maven-plugin` run,
`schema-gen-tools` requires a paired `@EventMapping` on every `@GenerateSchema` type, validates
type shape and effective metadata, generates JSON Schemas as today, and atomically replaces:

```
${project.build.outputDirectory}/META-INF/event-mappings.idx
```

Index rules:

| Rule | Detail |
|---|---|
| Lifecycle | **Build output only** — never under `src/main/resources`; do **not** commit |
| Format | UTF-8; one event FQCN per line; lexically sorted; final trailing newline |
| Contents | FQCNs only — no timestamps, paths, or annotation payloads |
| Drift gate | None for the index (unlike committed schemas under `src/main/resources/schemas/`) |
| Packaging | The contracts jar must contain the index; IT/package asserts this |

Build fails on unpaired annotations, blank required attributes, unsupported type shapes (nested,
non-record, non-public, etc.), duplicate Java types, duplicate effective `(groupId, artifactId)`
within a module, or deterministic bean-name collisions within a module.

### Runtime registration from the index

Each `*TypeMappingAutoConfiguration` stays `@AutoConfiguration` and is thinned to:

```java
@AutoConfiguration
@RegisterEventMappings("com.example.contracts.orders")
public class OrderTypeMappingAutoConfiguration {}
```

`@RegisterEventMappings` imports `EventMappingRegistrar` (`ImportBeanDefinitionRegistrar`). The
registrar:

1. Loads and merges every classpath `META-INF/event-mappings.idx` (no package scan).
2. Validates globally (annotation/index mismatch, duplicate coordinates across jars, bean-name
   collisions across jars) before filtering.
3. Keeps only types whose `class.getPackageName()` **equals** the imported package (exact match —
   not `startsWith`). Event records must stay in that package.
4. Builds each `TypeMapping` **only** via `Mappings.forDomain(...).json(...)`.
5. Registers bean name `Introspector.decapitalize(simpleName) + "Mapping"` (e.g.
   `OrderCreated` → `orderCreatedMapping`), **skipping** when
   `registry.containsBeanDefinition(beanName)` — app/user beans win.

Publisher topology remains opt-in (`*PublisherTopology`, not in `AutoConfiguration.imports`).
Mapping registration never declares exchanges. `schema-messaging-core` still injects
`List<TypeMapping>` and needs no mapping-discovery change.

### Two different knobs (do not conflate)

| Knob | What it matches | Purpose |
|---|---|---|
| **Bean-name override** | Spring bean name (`orderCreatedMapping`) | Replace one mapping's `TypeMapping` instance; registrar skips that name |
| **`TypeMappingSelection`** (`events.mappings.include` / `exclude`) | Simple name, FQCN, or `groupId:artifactId` — **not** bean names | Filter which classpath mappings enter `TypeMappingRegistry` / catalog warm |

### Exact-package invariant

`@RegisterEventMappings("com.example.contracts.orders")` registers only types in that exact
package. A record in `…orders.nested` is invisible to the orders auto-config. Keep domain event
records in the package named by the domain's `@RegisterEventMappings`.

## Rejected alternatives

### Runtime classpath package scan

Would reintroduce discovery at startup, lose the build-time fail-fast pairing with
`@GenerateSchema`, and make "which types are events" depend on classpath composition rather than
a deterministic index. Not built.

### Keep hand-written `@Bean` methods alongside annotations

Half-step that still drifts. Spec §13 forbids annotation-without-index or retaining manual beans
as an intermediate state.

### Commit the index under `src/main/resources`

Would invent a second drift gate for a file that is pure build output. Schemas stay committed
(human-reviewable contract artifact); the index is regenerated every `process-classes` and shipped
only inside the jar.

## Consequences

- **Positive:** New-event onboarding is annotate + build + commit schema — no mapping `@Bean`.
- **Positive:** Build-time pairing of `@GenerateSchema` / `@EventMapping` fails the module build
  before a broken jar reaches a service.
- **Positive:** Named override semantics and `Mappings` as the single construction path are
  preserved from ADR-0007; ADR-0008 exchange ownership is unchanged.
- **Neutral:** `schema-messaging-core` unchanged for mapping discovery.
- **Neutral:** Index is invisible in git; a dirty/missing `target/` means regenerate via
  `process-classes` / `package`.
- **Failure modes (build):** unpaired/blank/invalid annotations; coordinate or bean-name
  collisions within a contracts module.
- **Failure modes (startup):** missing/malformed/empty index for an active contracts auto-config;
  annotation/index mismatch; duplicate `(groupId, artifactId)` or bean names across jars; exact
  package matches zero indexed events.
