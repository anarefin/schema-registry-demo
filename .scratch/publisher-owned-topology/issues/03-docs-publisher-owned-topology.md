# 03 — Docs reflect publisher-owned exchanges / consumer-owned queues

**What to build:** Bring the project's docs in line with the new ownership model so a reader
understands that exchanges are publisher-owned, private queues are consumer-owned, and broker
permissions are least-privilege per role.

**Blocked by:** 01 — Core declares only queues + bindings; 02 — Contracts publisher-owned exchanges + wiring.

**Status:** ready-for-agent

- [x] New `docs/adr/ADR-0008-publisher-owned-messaging-topology.md`: problem statement; the three
      options weighed (publisher-owns vs central `definitions.json` provisioning vs each-app
      idempotent declaration) and why publisher-owns wins for a single-publisher-per-domain model;
      decision; consequences — explicitly reverses the earlier "contracts own exchanges" decision,
      adds least-privilege consumers and boot-order handling.
- [x] `CONTEXT.md` glossary gains *publisher-owned exchanges*, *consumer-owned private queues*, and
      *least-privilege broker roles*.
- [x] The `*-contracts` and `schema-messaging-core` bullets in `CLAUDE.md`, plus `docs/TUTORIAL.md`
      and `README.md`, describe publisher-owned exchanges + consumer-owned queues and the permission
      split: publisher gets `configure`+`write` on its domain exchanges; consumer gets `read`/`bind`
      + `configure` on its own queues; no consumer `configure` on exchanges.
