# 07 — Per-service topology deduplicates exchange declaration

**What to build:** Per-service queue topology declaration must declare each distinct exchange (main, DLX, retry) at most once per application startup, regardless of how many event types the service handles. Exchange durability and other properties must come from the domain contracts' exchange beans — not a second hardcoded copy maintained independently in core.

**Blocked by:** 03 — DLX/retry exchange naming parity enforced; 04 — Retry tier TTL configured in one place

**Status:** ready-for-agent

- [ ] A service handling all order and customer event types issues one `declareExchange` per distinct exchange name, not three per event type
- [ ] Exchange properties (durable, auto-delete, etc.) match the contracts module exchange beans
- [ ] Per-service queues, DLQs, and retry ladders for each handled event are still declared correctly
- [ ] Integration test or observable assertion confirms exchange declare count is bounded by distinct exchange names
