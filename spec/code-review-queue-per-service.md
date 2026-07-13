# Code Review — `improve/queue-per-service` (vs `main`)

Reviewed with `/code-review` (high effort: 8 finder angles, 1-vote verify pass).
Diff scope: `git diff main...HEAD` (9 commits, 140 files — branch not yet merged to `main`).

10 findings, ranked most-severe first. All were independently re-verified against the
current code before being listed here.

## 1. `OrderController.fulfillOrder` NPEs instead of returning the documented 400

**File:** `producer-service/src/main/java/com/example/producer/controller/OrderController.java:94`
**Category:** correctness — **CONFIRMED**

The class javadoc promises schema validation is the single validation authority (a
violation throws `SchemaValidationException` → 400, no message emitted). `fulfillOrder`
breaks that contract: it dereferences `request.buyer().customerId()`, `request.shipping()...`,
`request.payment()...` while building the domain event, *before* the converter/validator
ever runs.

**Failure scenario:** `POST /api/orders/fulfill` with a body missing `"buyer"`. This same
PR adds `NoBeanValidationWebMvcConfiguration` (disables Bean Validation) and there's no
`@Valid` on the `@RequestBody`, so `request.buyer()` deserializes to `null`.
`request.buyer().customerId()` throws an NPE — `GlobalExceptionHandler` only catches
`SchemaValidationException`, so this surfaces as an unhandled 500 instead of the documented
400.

## 2. Old per-domain queues are orphaned with no decommission path

**File:** `schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java:81`
**Category:** correctness — **CONFIRMED**

Queue naming moved from shared per-domain (`orders.created.queue`) to per-service
(`orders.created.<service>.queue`). `OrderTopologyAutoConfiguration` /
`CustomerTopologyAutoConfiguration` had their old queue/DLQ-declaring beans deleted
outright; the new `ServiceQueueTopologyAutoConfiguration` only ever declares the new names.

**Failure scenario:** Deploying this branch over an existing RabbitMQ broker with persistent
storage (i.e. anything other than this repo's own ephemeral, no-volume docker-compose): the
old durable `orders.created.queue`/`.dlq` are still bound to `events.orders.exchange` with
routing key `orders.created` and are never redeclared or removed. RabbitMQ never deletes
queues an app simply stops declaring, so they keep absorbing a duplicate copy of every
published event forever, with nothing draining them — unbounded growth on the broker.

## 3. DLX/retry exchange names duplicated as literals, no parity test

**File:** `order-contracts/src/main/java/com/example/contracts/orders/OrderEventRouting.java:31`
**Category:** correctness — **CONFIRMED**

`OrderEventRouting`/`CustomerEventRouting` hardcode `DLX`/`RETRY_EXCHANGE` as literal
strings, consumed by the contracts modules' own `TopicExchange` beans. Separately,
`ServiceQueueTopologyAutoConfiguration` and `DlxMessageRecoverer` compute the *same* names
at runtime via `TopologyNaming.dlxExchangeName`/`retryExchangeName`. Currently consistent —
no test enforces that they stay that way.

**Failure scenario:** If `TopologyNaming`'s suffix convention ever changes (e.g. `.dlx` →
`.dead-letter`), the two code paths silently diverge: failed messages get routed to a DLX
exchange no queue is bound to, and are dropped with no error.

## 4. `@BitsEventHandler` scanners use the proxy class, not the AOP target class

**File:** `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/BitsEventHandlerScanner.java:47`
**Category:** correctness (latent) — **CONFIRMED**

`BitsEventHandlerScanner` and `BitsEventHandlerRegistrar` both scan `bean.getClass()`
instead of unwrapping via `AopUtils.getTargetClass(bean)` — the same defensive step
Spring's own `EventListenerMethodProcessor` performs before an equivalent method scan.

**Failure scenario:** The moment a `@BitsEventHandler` method also gets a
proxy-triggering annotation (`@Transactional`, `@Async`, `@Cacheable`, ...),
`bean.getClass()` returns the CGLIB proxy subclass, whose overridden methods don't carry
the original's annotations. The handler silently gets no listener registered and no queue
declared — no error, no log, that event type is just never consumed. Not live today (no
current handler bean is proxied), but a real landmine for the next contributor.

## 5. Health indicator swallows RabbitMQ query errors as depth 0

**File:** `consumer-service/src/main/java/com/example/consumer/health/QueueDepthHealthIndicator.java:83`
**Category:** correctness / reliability — **CONFIRMED** (pre-existing, not introduced by this diff)

`queueDepth()` catches any exception from `rabbitAdmin.getQueueInfo(...)` and returns `0`.

**Failure scenario:** A transient AMQP channel error (or the DLQ not yet declared if a probe
races `ServiceQueueTopologyAutoConfiguration`'s new startup-time declaration) makes the
query throw; `dlqDepth` becomes `0`, `dlqEmpty` stays `true`, and `/actuator/health` reports
`Health.up()` even though the DLQ was never actually confirmed empty. A real outage is
indistinguishable from a genuinely empty DLQ to anything alerting on UP/DOWN status. This
bug predates the branch, but the new per-service startup-ordering path makes the trigger
more plausible than before.

## 6. New topology-declaration code in `schema-messaging-core` contradicts CLAUDE.md

**File:** `schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java:96`
**Category:** conventions — **CONFIRMED**

CLAUDE.md (edited in this same diff, lines 120–121 and 179) states core "Owns no AMQP
topology" and that topology is "owned per-domain, not by core." This diff's own code
contradicts it: `OrderTopologyAutoConfiguration`/`CustomerTopologyAutoConfiguration` had
their queue/DLQ/retry-ladder beans deleted, and the new `ServiceQueueTopologyAutoConfiguration`
in `schema-messaging-core` (core) now owns that declaration instead.

**Impact:** A contributor trusting CLAUDE.md would add topology changes to a `*-contracts`
module expecting it to be authoritative — it wouldn't be. No maven-enforcer rule catches
this since it only checks compile-time dependency direction, not code placement.

## 7. Health endpoint now does a full reflective bean scan on every poll

**File:** `consumer-service/src/main/java/com/example/consumer/health/QueueDepthHealthIndicator.java:59`
**Category:** efficiency — **CONFIRMED** (new regression)

`health()` calls `BitsEventHandlerScanner.discoverHandledTypeMappings(...)` on every
invocation. The pre-diff version simply iterated `typeMappingRegistry.all()`.

**Failure scenario:** With no Actuator health-cache TTL configured, every liveness/readiness
probe (e.g. every 5–10s under Kubernetes) triggers `getBeanDefinitionNames()` + `getBean()`
for every bean in the context plus a reflection scan for `@BitsEventHandler` — wasted work
that scales with total bean count, even though the handled-event-type set is fixed at
startup and already computed once by `ServiceQueueTopologyAutoConfiguration`'s
`SmartInitializingSingleton`.

## 8. Redundant exchange (re)declaration and duplicated exchange properties

**File:** `schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java:83`
**Category:** efficiency — **CONFIRMED**

`declareForMapping` issues 3 `declareExchange` RPCs per event type instead of deduping by
exchange name, and hardcodes `durable`/`autoDelete` as a second copy of what the contracts
modules' `TopicExchange` beans already declare.

**Failure scenario:** A service handling all 7 event types issues 21 `declareExchange`
round-trips for only 6 distinct exchanges. If a contracts module's exchange bean is ever
changed (new arguments, different durability), only one of the two independently-maintained
copies may get updated, risking a declare conflict or divergent behavior.

## 9. Retry TTL ladder duplicated across two auto-configuration classes

**File:** `schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingConsumerAutoConfiguration.java:43`
**Category:** reuse — **CONFIRMED**

`events.retry.tier{0,1,2}.ms` is independently bound with duplicated default literals in
both `SchemaMessagingConsumerAutoConfiguration` and `ServiceQueueTopologyAutoConfiguration`.

**Failure scenario:** Changing or adding a retry tier in one class without updating the
other silently desyncs `DlxMessageRecoverer`'s retry-count/delay decisions from the actual
queue TTLs — messages could hit the DLQ before exhausting the real TTL ladder, or vice
versa.

## 10. No dead-letter exchange on main queues if `recover()` throws

**File:** `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/DlxMessageRecoverer.java:63`
**Category:** correctness — **PLAUSIBLE**

`recover()` throws `IllegalStateException` if `receivedExchange` is blank; `DlxRoutingAdvice`
logs and rethrows the *original* exception on any routing failure, which gets nacked with
`requeue=false`. Main queues carry no `x-dead-letter-exchange` argument.

**Failure scenario:** If `recover()` throws for any reason, the message is discarded by
RabbitMQ with no trace. The specific blank-`receivedExchange` trigger cited isn't realistically
reachable in production (RabbitMQ always populates it for broker-routed deliveries — only a
hand-built unit test exercises this branch), but the missing DLX safety net on main queues is
a real structural gap for any other exception path inside `recover()` (e.g. a broker
connection failure during the DLQ/retry publish).

---

*Not included above (lower severity, cut for the 10-finding cap): stale
`docs/adr/0004`–`0008` and `docs/TODO.md` references left dangling in CLAUDE.md/CONTEXT.md/
TUTORIAL.md after those files were deleted in commit `c0f9738`.*
