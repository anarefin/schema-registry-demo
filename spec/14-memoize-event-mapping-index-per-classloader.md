# 14 — Memoize the merged event-mapping index per class loader

**Status:** Proposed · **Type:** Performance / internal refactor
**Depends on:** Spec 13 (annotation-driven event mappings) — shipped.

## Problem

`EventMappingRegistrar.registerBeanDefinitions` calls `IndexedEventMappings.load(loader)` fresh on
every `@RegisterEventMappings` import
([EventMappingRegistrar.java](../event-contract-kit/src/main/java/com/example/amqp/topology/mapping/EventMappingRegistrar.java)),
then discards all entries outside the target package via `inPackage(...)`
([IndexedEventMappings.java](../event-contract-kit/src/main/java/com/example/amqp/topology/mapping/IndexedEventMappings.java)).

With `N` contracts auto-configs (orders + customers = 2 today) the full pipeline runs `N` times:

- `classLoader.getResources("META-INF/event-mappings.idx")` enumerates the whole classpath
- `Class.forName(fqcn, false, loader)` for **every** FQCN across **all** domains
- `@EventMapping` reflection + `TypeMapping` build for each
- global duplicate-coordinate + duplicate-bean-name validation across the whole classpath

Cost is `O(domains × totalEvents)`, and the identical global validation runs once per domain. The
merged index is a pure function of the class loader's resources, so it is the same object every
time — recomputing it per registrar is wasted work.

## What to build

Compute the merged, validated `IndexedEventMappings` **once per class loader** and reuse it across
all registrars. Preserve every existing semantic:

- Same global validation (annotation/index mismatch, duplicate coordinates, duplicate bean names).
- Same exact-package filtering (`inPackage`, `equals` not prefix).
- Same fail-fast behavior and exception types/messages.
- Same app-override (`containsBeanDefinition` skip) behavior.

No change to the index format, `schema-gen-tools`, contracts records, `Mappings`, or
`schema-messaging-core`.

## Design

Add a memoizing accessor to `IndexedEventMappings`. Keep the existing `load(ClassLoader)` as the
uncached builder (still used internally and directly by unit tests); add `forClassLoader` as the
cached entry point.

```java
public final class IndexedEventMappings {

    // Weak keys: a closed/GC-eligible ClassLoader (e.g. per-test URLClassLoader, or a hot-reload
    // loader) must not be pinned by this cache. WeakHashMap is not thread-safe, so guard access.
    private static final Map<ClassLoader, IndexedEventMappings> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cached per class loader. The merged index is a pure function of the loader's resources. */
    public static IndexedEventMappings forClassLoader(ClassLoader classLoader) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(classLoader, IndexedEventMappings::load);
        }
    }

    /** Uncached build (existing behavior). Still used by forClassLoader and by tests. */
    public static IndexedEventMappings load(ClassLoader classLoader) { /* unchanged */ }
}
```

`EventMappingRegistrar` switches its one call site:

```java
List<IndexedEventMappings.Entry> events =
        IndexedEventMappings.forClassLoader(loader).inPackage(targetPackage);
```

### Design notes / decisions

- **Weak keys, not `ConcurrentHashMap`.** Tests and any dynamic-classloading scenario create and
  close loaders; strong keys would leak them (and their loaded classes). `WeakHashMap` +
  `synchronized` block is correct and simple; `computeIfAbsent` runs inside the lock so concurrent
  context refreshes build the index at most once.
- **Do not cache failures.** If `load` throws (annotation/index mismatch, duplicate coordinates),
  nothing is stored; a later registrar re-runs and throws the same deterministic error. Acceptable
  — startup aborts either way.
- **Values do not pin their key.** `IndexedEventMappings` holds `Class<?>` / `TypeMapping` loaded
  by the key loader; those classes reference the loader, forming a value→key strong path that would
  defeat `WeakHashMap`. Mitigation: this cache lives in `event-contract-kit`, whose classes are
  typically loaded by a parent/app loader, and cache lifetime matches JVM/app lifetime in
  production. For per-test `URLClassLoader`s the entries are dropped when the test drops its own
  strong ref to the loader and the map is queried again; acceptable for the POC. (If strict
  reclamation is ever needed, switch to keying by a `WeakReference<ClassLoader>` with an explicit
  eviction pass — out of scope here.)
- **No public API removed.** `load` stays public; only the registrar's call site changes.

## Changes

- [IndexedEventMappings.java](../event-contract-kit/src/main/java/com/example/amqp/topology/mapping/IndexedEventMappings.java)
  — add `CACHE` + `forClassLoader`; keep `load` as-is. Imports: `java.util.Collections`,
  `java.util.WeakHashMap`, `java.util.Map`.
- [EventMappingRegistrar.java](../event-contract-kit/src/main/java/com/example/amqp/topology/mapping/EventMappingRegistrar.java)
  — change the single `load(loader)` call to `forClassLoader(loader)`.

No other production changes.

## Tests

Add to
[IndexedEventMappingsTest.java](../event-contract-kit/src/test/java/com/example/amqp/topology/mapping/IndexedEventMappingsTest.java):

- `forClassLoader` returns the **same instance** on repeated calls with the same loader
  (`assertThat(a).isSameAs(b)`) — proves single build.
- Different class loaders get **distinct** instances.
- `forClassLoader` result is content-equal to a fresh `load` (delegation correctness).

Regression (must stay green, unchanged):

- [EventMappingRegistrarTest.java](../event-contract-kit/src/test/java/com/example/amqp/topology/mapping/EventMappingRegistrarTest.java)
  — multi-jar scoping, app override, fail-fast on empty/blank package. Each test uses a fresh
  `URLClassLoader`, so cache entries stay isolated across cases.
- [IndexedEventMappingsTest.java](../event-contract-kit/src/test/java/com/example/amqp/topology/mapping/IndexedEventMappingsTest.java)
  — existing duplicate-coordinate / bean-name / annotation-mismatch failures.

## Acceptance criteria

- [ ] `IndexedEventMappings.forClassLoader` memoizes per class loader with weak keys.
- [ ] `EventMappingRegistrar` uses `forClassLoader`; the merged index is built once per loader
      regardless of how many `@RegisterEventMappings` imports exist.
- [ ] All existing global validation, exact-package filtering, fail-fast, and app-override
      semantics are byte-for-byte preserved (existing tests unchanged).
- [ ] New same-instance / distinct-loader tests pass.
- [ ] No closed-`URLClassLoader` leak introduced by the cache (weak keys).
- [ ] `schema-gen-tools`, contracts records, `Mappings`, and `schema-messaging-core` are untouched.
- [ ] Focused suite passes:
      `./mvnw -pl event-contract-kit,order-contracts,customer-contracts,schema-messaging-core -am test`.
- [ ] Full unit suite passes: `./mvnw test`.

## Phasing

Single PR. Two-file production change plus tests.

## Blocked by

None.
