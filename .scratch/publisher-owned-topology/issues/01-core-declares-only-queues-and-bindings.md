# 01 — Core declares only queues + bindings, never exchanges

**What to build:** `schema-messaging-core`'s per-service topology configurer stops declaring
domain exchanges entirely — it declares only the service's private queues, DLQs, retry ladders,
and their bindings. It no longer depends on exchange `@Bean`s being present in the context: it
constructs the main/DLX/retry `TopicExchange` objects it needs *locally* from the mapping's
exchange **name** (`DomainTopology.of(mapping.exchange())`), solely to feed
`EventTopologyFactory.declarablesForEvent(...)`. A binding only needs the exchange *name*, so no
`declareExchange` call is required or made. The consumer's `RabbitAdmin` is set to
`ignoreDeclarationExceptions(true)` so a consumer that boots before its publisher self-heals on
reconnect instead of failing context refresh.

This is invisible to running services today (contracts still auto-declare exchanges, so exchanges
still exist on the broker) — it removes core's double-declaration and its cross-role reach into
exchange beans, setting up the ownership flip in ticket 02.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `ServiceQueueTopologyConfigurer` no longer injects `List<TopicExchange>`; `exchangesByName`,
      `requireExchange`, and `declareExchangeOnce` are removed.
- [ ] Main/DLX/retry `TopicExchange` objects are built locally from the mapping's exchange name and
      passed only into `EventTopologyFactory.declarablesForEvent(...)`; core invokes `declareExchange`
      **zero** times.
- [ ] Per-service queues, DLQs, retry ladders, and bindings for every `@BitsEventHandler`-handled
      event are still declared correctly; legacy shared-domain queue decommission still runs.
- [ ] Consumer `RabbitAdmin` has `ignoreDeclarationExceptions(true)`.
- [ ] `ServiceQueueTopologyExchangeDeduplicationTest` is repurposed to assert the configurer never
      calls `rabbitAdmin.declareExchange(...)` (declares only queues + bindings).
- [ ] `ServiceQueueTopologyLegacyDecommissionTest` drops its `TopicExchange` fixtures.
- [ ] `core ↛ contracts` still holds (kit + exchange-name string only); `./mvnw -pl schema-messaging-core test` passes.
