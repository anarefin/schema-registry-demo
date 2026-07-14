# 10 — Track `SchemaFileNaming` duplication (watch item)

**Severity:** Low · **Finding:** ARCH-002 · **Type:** Watch / no-action-yet

## What to build

The class-name→filename algorithm exists verbatim in two modules — `schema-messaging-core/.../schema/SchemaFileNaming.java`
and `schema-gen-tools/.../SchemaFileNaming.java` — which must agree byte-for-byte, or a running service
silently fails to locate its schema. This is a **deliberate, documented** tradeoff (the two modules must
stay dependency-free of each other) and is defended by parity tests in both modules, so the risk is
contained, not open.

**No change is required while the parity tests hold.** This ticket exists to keep the coupling-by-convention
visible. If a third consumer of the convention ever appears, extract the algorithm into `event-contract-kit`
(already a shared leaf both could depend on) rather than adding a third copy.

## Acceptance criteria

- [ ] Parity tests in both modules remain present and green (no regression).
- [ ] Trigger for action documented: extract to `event-contract-kit` if a third consumer of the naming convention appears.
- [ ] No code change made unless the trigger condition is met.

## Blocked by

None — this is a standing watch item, not active work.
