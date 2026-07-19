# 01 — Memoize the merged event-mapping index per class loader

**What to build:** The merged, validated `IndexedEventMappings` is built **once per class loader**
and reused across every `@RegisterEventMappings` import. Add a memoizing accessor
`IndexedEventMappings.forClassLoader(ClassLoader)` backed by a weak-keyed, synchronized cache
(`Collections.synchronizedMap(new WeakHashMap<>())`, `computeIfAbsent` inside the lock); keep
`load(ClassLoader)` as the uncached builder. `EventMappingRegistrar` switches its single call site
from `load(loader)` to `forClassLoader(loader)`. Every existing semantic is preserved byte-for-byte:
global validation (annotation/index mismatch, duplicate coordinates, duplicate bean names), exact
`inPackage` filtering (`equals`, not prefix), fail-fast behavior + exception types/messages, and
app-override (`containsBeanDefinition` skip). Failures are not cached — a throwing `load` stores
nothing and a later registrar re-throws the same deterministic error. Weak keys ensure a
closed/GC-eligible `URLClassLoader` (per-test or hot-reload) is not pinned.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `IndexedEventMappings.forClassLoader` memoizes per class loader with weak keys.
- [ ] `EventMappingRegistrar` uses `forClassLoader`; the merged index is built once per loader
      regardless of how many `@RegisterEventMappings` imports exist.
- [ ] All existing global validation, exact-package filtering, fail-fast, and app-override
      semantics preserved (existing tests unchanged).
- [ ] New tests: `forClassLoader` returns the same instance on repeated same-loader calls;
      distinct loaders get distinct instances; result is content-equal to a fresh `load`.
- [ ] No closed-`URLClassLoader` leak introduced by the cache (weak keys).
- [ ] `schema-gen-tools`, contracts records, `Mappings`, and `schema-messaging-core` untouched.
- [ ] Focused suite passes:
      `./mvnw -pl event-contract-kit,order-contracts,customer-contracts,schema-messaging-core -am test`.
- [ ] Full unit suite passes: `./mvnw test`.
