# 08 — Legacy shared-domain queues decommissioned on deploy

**What to build:** Deploying the per-service queue model over an existing RabbitMQ broker that still has the old shared per-domain queues (`orders.created.queue`, matching DLQs, etc.) must include a decommission path so those orphaned queues stop absorbing duplicate copies of every published event. The old durable queues bound to domain exchanges with plain routing keys must be removed or drained — they cannot be left forever with nothing consuming them.

**Blocked by:** 07 — Per-service topology deduplicates exchange declaration

**Status:** ready-for-agent

- [ ] Startup or migration step identifies legacy shared-domain queue names superseded by per-service naming
- [ ] Legacy queues are deleted (or bindings removed and queues drained) idempotently on deploy
- [ ] Safe on a fresh broker (no-op when legacy queues do not exist)
- [ ] Documented operator guidance for persistent-broker upgrades (not just ephemeral docker-compose)
- [ ] Verify no duplicate fan-out copy accumulates on legacy queue names after deploy
