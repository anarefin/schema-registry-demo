# 01 — `@EventMapping` + bean-name helper in event-contract-kit

**What to build:** Domain-agnostic `@EventMapping` lands in `event-contract-kit` with the locked
attribute set (`groupId`, `exchange`, `routingKey`, optional `artifactId`, default
`SchemaType.JSON`). Empty `artifactId` means the Java simple name. A shared helper computes
deterministic Spring bean names as `Introspector.decapitalize(simpleName) + "Mapping"`, including
leading-acronym edge cases. Annotation stays free of Spring and of queue/DLQ/retry/consumer fields.
No contracts migration yet — this unlocks build-time and runtime work.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `@EventMapping` exists with the locked signature, retention, and target; defaults match the spec.
- [ ] Bean-name helper produces `orderCreatedMapping`, `customerAddressAddedMapping`, and
      `URLEventMapping` (and similar decapitalize edge cases).
- [ ] Annotation carries no Spring annotations or exchange-declaration behavior.
- [ ] Focused kit unit tests for the helper (and annotation defaults if exercised) pass.
