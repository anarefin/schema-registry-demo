# 07 — Non-instantiating `@BitsEventHandler` startup scan

**Severity:** Medium · **Finding:** PERF-002 · **Type:** AFK

## What to build

`BitsEventHandlerRegistrar.configureRabbitListeners` and
`BitsEventHandlerScanner.discoverHandledTypeMappings` both iterate
`applicationContext.getBeanDefinitionNames()` and call `getBean(beanName)` on each to reflectively
inspect for `@BitsEventHandler`. This forces instantiation of *every* bean (defeating any `@Lazy`),
creates-and-discards prototype-scoped beans, and can trigger side effects on beans never meant to be
eagerly resolved. Cost scales with total bean count, not handler count.

Resolve types without instantiating — use `applicationContext.getType(beanName)` /
`ConfigurableListableBeanFactory.getBeanNamesForType`, or restrict the scan to `@Component`
candidates — and only `getBean()` the beans that actually declare a handler method.

## Acceptance criteria

- [ ] Handler discovery uses type resolution (`getType` / `getBeanNamesForType`) — no blanket `getBean()`.
- [ ] Only beans that declare a `@BitsEventHandler` method are instantiated during the scan.
- [ ] `@Lazy` beans and prototype-scoped beans are not eagerly resolved by the scan.
- [ ] Discovered handler set is identical to before (behavior-preserving); tests cover it.

## Blocked by

None — can start immediately.
