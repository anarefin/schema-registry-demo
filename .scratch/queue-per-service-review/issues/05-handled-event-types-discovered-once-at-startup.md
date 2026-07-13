# 05 — Handled event types discovered once at startup

**What to build:** The set of event types this service actually handles (derived from `@BitsEventHandler` methods) must be computed once during application startup and exposed as a reusable bean or component. Per-service queue topology declaration and the queue depth health indicator must consume this cached set instead of re-scanning the entire application context on every use.

**Blocked by:** 02 — BitsEventHandler discovery unwraps AOP proxies

**Status:** ready-for-agent

- [ ] Handled `TypeMapping` set is computed once after singletons are instantiated (same lifecycle hook topology declaration already uses)
- [ ] Queue topology declaration reads from the cached set
- [ ] Queue depth health indicator reads from the cached set
- [ ] No `getBeanDefinitionNames()` + per-bean reflection scan on health probe invocations
- [ ] All three consumers (registrar, topology, health) agree on the same handled set
