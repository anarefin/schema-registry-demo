# 03 — Ownership regression tests + doc sweep

**What to build:** Prove the ownership split still holds and record the decision. Confirm the
existing regression tests still pass unchanged — a contracts jar declares no topology without
handlers, and core's per-service topology declares no exchanges. Then sweep the docs so onboarding
describes the two distinct processor outputs and their different activation rules:
`GeneratedEventTypeMappings` auto-loads through `AutoConfiguration.imports`;
`*sPublisherTopology` is **never** auto-loaded and requires an explicit publisher `@Import`. ADR-0011
gains the second processor output; ADR-0008 keeps its publisher-owned, opt-in exchange story but
now points at the generated class; README, TUTORIAL, CONTEXT, and CLAUDE.md update their import
examples to `OrdersPublisherTopology` / `CustomersPublisherTopology`. The full fast suite and the
Testcontainers integration suite pass.

**Blocked by:** 02 — Cut producer over to generated topology

**Status:** ready-for-agent

> Final ticket of the single change set (see `spec/16` Phasing) — 01, 02, 03 merge together.

- [ ] `ContractsJarDeclaresNoTopologyWithoutHandlersTest` and
      `ServiceQueueTopologyDeclaresNoExchangesTest` verified still green (adjust only if they name the
      old hand-written class).
- [ ] ADR-0011 documents the two processor outputs and their activation rules; ADR-0008 updated to
      reference the generated publisher topology while preserving publisher-owned, opt-in ownership.
- [ ] README, `docs/TUTORIAL.md`, `CONTEXT.md`, and `CLAUDE.md` describe the generated publisher
      topology and use `OrdersPublisherTopology` / `CustomersPublisherTopology` in import examples;
      no references to the deleted hand-written classes remain.
- [ ] `./gradlew test` (fast unit) and `./gradlew check` (Testcontainers integration) both pass.
