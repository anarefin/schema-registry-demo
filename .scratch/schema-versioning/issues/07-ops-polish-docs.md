# 07 — Ops polish (CLAUDE + DLQ + contentHash defer)

**What to build:** After Phase 1 is green, clean ops/docs debt: remove stale Schema Version Pinning / `StartupSchemaValidator` guidance from `CLAUDE.md`; document DLQ queue-depth alert expectation and the poison drill (`POST /api/orders/poison`); note quarterly field census (`@Deprecated` + `@BitsEventHandler` inventory); and state explicitly that wire `contentHash` is deferred — advisory-only if ever added, never a reject gate.

**Blocked by:** 01 — Defend tolerant reader; 02 — Generator additionalProperties invariant; 03 — Apicurio register canonicalize.

**Status:** ready-for-agent

- [ ] Stale Schema Version Pinning / `StartupSchemaValidator` removed from `CLAUDE.md`
- [ ] DLQ alert + poison-drill notes documented (TESTING-GUIDE or linked ops note)
- [ ] Field-census cadence mentioned for deprecation/removal discipline
- [ ] `contentHash` explicitly deferred (or advisory-only if ops later requests it — never reject)
