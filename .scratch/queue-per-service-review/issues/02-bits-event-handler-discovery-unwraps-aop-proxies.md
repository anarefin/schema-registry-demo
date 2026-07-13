# 02 — BitsEventHandler discovery unwraps AOP proxies

**What to build:** `@BitsEventHandler` method discovery must inspect the real target class, not the Spring AOP proxy subclass. A handler bean wrapped by CGLIB (e.g. because it also carries `@Transactional`, `@Async`, or `@Cacheable`) must still have its listener registered and its per-service queue declared — silently skipping proxied handlers is not acceptable.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Discovery uses `AopUtils.getTargetClass` (or equivalent) before scanning for `@BitsEventHandler` methods
- [ ] Both the scanner and the registrar apply the same unwrapping
- [ ] Unit or integration test proves a proxied handler bean gets a listener and queue declared
- [ ] Existing handler registration and topology declaration tests still pass
