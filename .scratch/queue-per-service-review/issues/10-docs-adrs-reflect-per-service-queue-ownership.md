# 10 — Docs/ADRs reflect per-service queue ownership

**What to build:** Project documentation must accurately describe the per-service queue architecture after the queue-per-service branch: domain contracts modules own domain exchanges; schema-messaging-core owns per-service queue/DLQ/retry-ladder declaration driven by `@BitsEventHandler` discovery. Stale references to deleted ADRs and outdated topology-ownership statements in CLAUDE.md, CONTEXT.md, and TUTORIAL.md must be reconciled.

**Blocked by:** 07 — Per-service topology deduplicates exchange declaration; 08 — Legacy shared-domain queues decommissioned on deploy

**Status:** ready-for-agent

- [x] CLAUDE.md no longer states core "owns no AMQP topology" without qualifying per-service queue ownership
- [x] CONTEXT.md and TUTORIAL.md topology sections match the contracts-own-exchanges / core-own-queues split
- [x] Dangling references to deleted ADRs (0004–0008) and stale TODO entries are removed or updated
- [x] Architecture description is consistent across all touched docs — no contradictory ownership claims
