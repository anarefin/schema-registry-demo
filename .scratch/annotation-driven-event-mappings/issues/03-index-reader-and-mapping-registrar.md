# 03 — Index reader + `ImportBeanDefinitionRegistrar` in event-contract-kit

**What to build:** Runtime registration reads all classpath `META-INF/event-mappings.idx`
resources, merges them (FQCN de-dupe; fail on duplicate coords across jars or annotation/index
mismatch), filters by exact package equality, builds each `TypeMapping` only via `Mappings`, and
registers named beans through a shared `ImportBeanDefinitionRegistrar` that skips when
`registry.containsBeanDefinition(beanName)` (app override wins). No runtime package scanning.
No contracts migration yet — Spring/fixture tests prove the path.

**Blocked by:** 01 — `@EventMapping` + bean-name helper in event-contract-kit

**Status:** ready-for-agent

- [ ] Multi-jar index load/merge and FQCN de-duplication behave as specified; malformed/missing/
      empty-when-expected indexes fail startup.
- [ ] Exact package filter uses `equals`, not prefix matching.
- [ ] Annotation → `TypeMapping` goes only through `Mappings`; default and explicit artifact IDs work.
- [ ] Bean names match the locked algorithm; existing app `@Bean("orderCreatedMapping")` wins while
      other generated mappings still register.
- [ ] No runtime classpath package scan path exists.
- [ ] `./mvnw -pl event-contract-kit test` passes.
