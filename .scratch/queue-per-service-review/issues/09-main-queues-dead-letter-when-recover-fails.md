# 09 — Main queues dead-letter when recover() fails

**What to build:** Main per-service queues must carry a dead-letter exchange argument so that if `DlxMessageRecoverer.recover()` throws for any reason (broker connection failure during retry/DLQ publish, unexpected routing error), the message is routed to the domain DLX instead of being discarded by RabbitMQ with no trace. Main queues currently have no `x-dead-letter-exchange` safety net.

**Blocked by:** 07 — Per-service topology deduplicates exchange declaration

**Status:** ready-for-agent

- [ ] Per-service main queues declare `x-dead-letter-exchange` pointing at the domain DLX exchange
- [ ] A recover failure routes the message to the DLQ path (or DLX), not silent discard
- [ ] Normal transient/permanent failure routing through the recoverer is unchanged
- [ ] Test covers an exception path inside `recover()` and asserts the message is not lost
