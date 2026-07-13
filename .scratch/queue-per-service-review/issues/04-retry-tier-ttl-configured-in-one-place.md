# 04 — Retry tier TTL configured in one place

**What to build:** The three retry tier TTL values (`events.retry.tier0.ms`, `tier1.ms`, `tier2.ms`) must be bound in exactly one configuration location and consumed by both per-service queue declaration and the DLX message recoverer's retry-count/delay logic. Changing or adding a tier in one place must automatically apply everywhere.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Retry tier TTL defaults and property binding exist in a single shared configuration (not duplicated across two auto-configuration classes)
- [ ] Per-service queue retry ladders and `DlxMessageRecoverer` read from the same source
- [ ] Existing retry-ladder integration behaviour unchanged (same TTL values on the wire)
- [ ] Test or assertion proves both consumers receive identical tier arrays
