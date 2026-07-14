# Spring Boot Enterprise Production-Readiness Review

_Generated: 2026-07-14 11:34 · Reviewer framing: Principal Software Architect / JVM Performance Engineer / Spring Boot Expert / Security Architect / Distributed Systems Expert / Production Reliability Engineer_

---

## 1. Stack Summary

| Property | Value |
|----------|-------|
| Spring Boot | **4.1.0** (BOM imported via `spring-boot-dependencies`, not `spring-boot-starter-parent`) |
| JDK | **Java 25** (`maven.compiler.release=25`, `maven-toolchains-plugin` enforced, bytecode 69) |
| Language(s) | **Java only** (no Kotlin/Groovy) |
| Build tool | **Maven** (committed wrapper `./mvnw`) |
| Layout | **Multi-module**, 7 modules: `schema-messaging-core`, `event-contract-kit`, `order-contracts`, `customer-contracts`, `producer-service`, `consumer-service`, `schema-gen-tools` |
| Run mode | **Sequential inline** (small codebase; ~50 core classes) |
| Config | `application.yml` in producer-service (`:8081`) and consumer-service (`:8082`), each with a `prod` profile document |

**Areas run:** 1 Architecture & Design · 2 Spring Boot & JDK · 3 Performance & Runtime · 8 Quality & Scalability
**Areas skipped:** 4 Data & API · 5 Security & Resilience · 6 Observability & Testing · 7 Ops, Cloud & Build

> Scope note: several findings below (publisher reliability, the poison endpoint, DLQ-driven health) live at the boundary of the *skipped* Security/Resilience and Ops areas. They are reported here because the evidence surfaced squarely inside a selected section (Spring Boot best practices, Code Quality, Scalability). A full Security/Resilience and Ops pass is still recommended before production.

---

## 2. Executive Summary

This is a well-architected schema-governance messaging POC. Module boundaries are clean and **machine-enforced** (`core ↛ contracts`, `contracts ↛ core` via `maven-enforcer-plugin`), ownership is deliberately split (contracts own exchanges + `TypeMapping`; core owns per-service queues via `@BitsEventHandler` discovery), the code is idiomatic modern Java (records, sealed exception taxonomy via a `PermanentFailure` marker, switch pattern-matching), and documentation is unusually thorough. The design would score highly on an architecture rubric.

Production-readiness is a different bar, and the project — by its own `CLAUDE.md` admission a POC — has three gaps that block a straight promotion: a **demo "poison" endpoint** exposed in the production REST surface, **no RabbitMQ publisher confirms** (silent message loss on unroutable/nacked publish), and **single-threaded consumer concurrency** that caps throughput. None are architecturally deep; all are small, well-understood fixes.

**Go / No-Go verdict: 🔴 No-Go for production in current form** — gated by 3 High findings (unauthenticated poison backdoor, no publish reliability, un-tuned consumer concurrency). All three are localized and cheap to remediate; the underlying architecture is sound.

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 3 |
| Medium | 8 |
| Low | 8 |

---

## 3. Findings

## 1. Overall Architecture Review

### [ARCH-001] [Low] Consumer listener infrastructure auto-configures in the producer service
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — Module: schema-messaging-core
- Review: This `@AutoConfiguration` is unconditional, so a pure producer (no `@BitsEventHandler` methods) still instantiates `rabbitListenerContainerFactory`, `BitsEventHandlerRegistrar`, `DlxMessageRecoverer`, and `DlxRoutingAdvice` that it never uses. `RabbitAdmin` is legitimately needed by the producer to declare exchanges, but the listener stack is dead weight and blurs the producer/consumer boundary.
- Fix: Gate the listener-only beans behind a condition — e.g. `@ConditionalOnClass(RabbitListenerConfigurer.class)` is already true, so prefer a `@ConditionalOnProperty("events.consumer.enabled")` or split a `...ProducerAutoConfiguration` (RabbitAdmin only) from the consumer one. Keeps a producer's context free of unused listener machinery.

## 2. Multi-Module Structure

The module graph is exemplary — leaf `event-contract-kit`, domain-agnostic `core`, self-activating `*-contracts` via `AutoConfiguration.imports`, build-only `schema-gen-tools` kept off the runtime classpath, and both dependency-direction rules enforced by `maven-enforcer-plugin`. One residual item:

### [ARCH-002] [Low] `SchemaFileNaming` is duplicated across two modules
- Location: `schema-messaging-core/.../schema/SchemaFileNaming.java` and `schema-gen-tools/.../SchemaFileNaming.java` — Modules: schema-messaging-core, schema-gen-tools
- Review: The class-name→filename algorithm exists verbatim in two modules that must agree byte-for-byte, or a running service silently fails to locate its schema. This is a **deliberate, documented** tradeoff (the two modules must stay dependency-free of each other) and is defended by parity tests in both modules — so the risk is contained, not open. Flagged only so it stays visible as coupling-by-convention.
- Fix: No change required while parity tests hold. If a third consumer of the convention ever appears, reconsider extracting it into `event-contract-kit` (already a shared leaf both could depend on) rather than adding a third copy.

## 3. Spring Boot Best Practices

### [SPRING-001] [High] No RabbitMQ publisher confirms/returns — publishes can be lost silently
- Location: `schema-messaging-core/.../publisher/EventPublisher.java` (`publish`) — Module: schema-messaging-core
- Review: `rabbitTemplate.send(exchange, routingKey, message)` is fire-and-forget. No `spring.rabbitmq.publisher-confirm-type`, no `publisher-returns`, no `mandatory` flag, and no confirm/return callbacks are configured anywhere in the project. A broker nack, a connection drop mid-publish, or an unroutable message (e.g. a routing-key/exchange mismatch after a topology change) is dropped with no error surfaced to the caller — the controller still returns `201 Created`. For a system whose entire value proposition is *governed, reliable* eventing, this is the sharpest production gap.
- Fix: Set `spring.rabbitmq.publisher-confirm-type=correlated` and `spring.rabbitmq.publisher-returns=true`, enable `RabbitTemplate` `setMandatory(true)`, and register confirm/return callbacks so a nack or return becomes a failed publish (5xx + retry/outbox) instead of a silent drop. Consider a transactional outbox if exactly-once-ish delivery is ever required.

### [SPRING-002] [Medium] Retry tiers and topology flags bound via scattered `@Value` instead of `@ConfigurationProperties`
- Location: `schema-messaging-core/.../config/RetryTierPropertiesAutoConfiguration.java` and `ServiceQueueTopologyAutoConfiguration.java` — Module: schema-messaging-core
- Review: `events.retry.tier{0,1,2}.ms` and `events.topology.decommission-legacy-queues` are read through inline `@Value` defaults in multiple beans. This loses relaxed binding, IDE metadata, JSR-303 validation, and a single documented properties surface, and duplicates default values across sites.
- Fix: Introduce a `@ConfigurationProperties("events")` record (retry tiers as a `List<Duration>`, topology flags as nested types) with `@Validated`, registered via `@EnableConfigurationProperties`. Beans then inject the typed properties object.

### [SPRING-003] [Low] Field injection of `spring.application.name` in an auto-configuration
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java:49` (`@Value private String serviceName`) — Module: schema-messaging-core
- Review: Field injection in a configuration class is harder to test and inconsistent with the constructor-injection style used elsewhere. It also hard-fails context startup with an opaque error if `spring.application.name` is ever unset.
- Fix: Inject `serviceName` as a `@Bean` method parameter (as `ServiceQueueTopologyAutoConfiguration` and `QueueDepthHealthIndicator` already do), and give it a sensible default (`${spring.application.name:unknown-service}`) or fail with a clear message.

## 4. JDK Best Practices

### [JDK-001] [Medium] Java 25 runtime but virtual threads are not enabled
- Location: `producer-service/src/main/resources/application.yml`, `consumer-service/src/main/resources/application.yml` — Modules: producer-service, consumer-service
- Review: The stack is Java 25 with a blocking web tier and blocking (`SimpleMessageListenerContainer`) AMQP listeners — the exact profile that benefits most from virtual threads — yet `spring.threads.virtual.enabled` is unset. A cheap, well-supported scalability lever is being left on the table.
- Fix: Set `spring.threads.virtual.enabled=true` (Spring Boot 4 wires virtual-thread executors for Tomcat and the listener containers). Validate under load, since pinning on `synchronized` blocks (e.g. `HandledEventTypesCache.handledTypeMappings()`) can negate the benefit on hot paths — though that particular method runs once at startup.

### [JDK-002] [Low] `Collectors.toList()` where `Stream.toList()` is available
- Location: `schema-messaging-core/.../serde/JsonSchemaStrategy.java:119` (`validate`) — Module: schema-messaging-core
- Review: `.collect(Collectors.toList())` predates the JDK 16+ `Stream.toList()` idiom and returns a mutable list unnecessarily.
- Fix: Replace with `.limit(5).toList()`.

## 8. Performance Review

### [PERF-001] [Medium] JSON is parsed twice per message on both produce and consume paths
- Location: `schema-messaging-core/.../serde/JsonSchemaStrategy.java` (`serialize`, `deserialize`, `validate`) — Module: schema-messaging-core
- Review: On consume with `validateOnDeserialize=true` (the default), `validate()` calls `objectMapper.readTree(bytes)` and then `deserialize()` calls `objectMapper.readValue(bytes, type)` — the payload is fully parsed twice. On produce, `serialize()` does `writeValueAsBytes(payload)` then `validate()` re-parses those bytes with `readTree`. That doubles parse CPU and allocation for every message.
- Fix: Parse once. On consume, `JsonNode node = readTree(bytes)`, validate `node`, then `objectMapper.convertValue(node, targetType)`. On produce, `JsonNode node = objectMapper.valueToTree(payload)`, validate `node`, then `objectMapper.writeValueAsBytes(node)`. Eliminates the redundant parse without changing semantics.

### [PERF-002] [Medium] Startup scans call `getBean()` on every bean definition, forcing eager instantiation
- Location: `schema-messaging-core/.../consumer/BitsEventHandlerRegistrar.java` (`configureRabbitListeners`) and `BitsEventHandlerScanner.discoverHandledTypeMappings` — Module: schema-messaging-core
- Review: Both loops iterate `applicationContext.getBeanDefinitionNames()` and call `getBean(beanName)` on each to reflectively inspect for `@BitsEventHandler`. This forces instantiation of *every* bean (defeating any `@Lazy`), creates-and-discards prototype-scoped beans, and can trigger side effects on beans that were never meant to be eagerly resolved. Cost scales with total bean count, not handler count.
- Fix: Iterate bean definitions and resolve types without instantiating — use `applicationContext.getType(beanName)` / `ConfigurableListableBeanFactory.getBeanNamesForType`, or restrict the scan to `@Component`-annotated candidates, and only `getBean()` the beans that actually declare a handler method.

## 9. Memory Analysis

No issues found. Caches are fixed-size and bounded to the registered `TypeMapping` set (`LocalSchemaCatalog` schema bytes, `JsonSchemaStrategy.compiledSchemaCache`), DLQ stack traces are truncated to 4 KB, and no static/`ThreadLocal` accumulation or unbounded collection growth was observed.

## 10. Garbage Collection Review

### [GC-001] [Low] Elevated allocation churn from double JSON parsing under load
- Location: `schema-messaging-core/.../serde/JsonSchemaStrategy.java` — Module: schema-messaging-core
- Review: The redundant parse in [PERF-001] roughly doubles short-lived `JsonNode`/token allocation per message, raising young-gen churn proportionally to throughput. No long-lived allocation concern exists.
- Fix: Resolving [PERF-001] removes the extra allocation. The default G1 collector is appropriate for this workload; no collector change is warranted at current scale.

## 11. Concurrency Review

### [CONC-001] [Medium] Listener container concurrency and prefetch left at defaults (single consumer)
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` (`rabbitListenerContainerFactory`) — Module: schema-messaging-core
- Review: The `SimpleRabbitListenerContainerFactory` sets the converter, `defaultRequeueRejected=false`, and the advice chain, but never sets `concurrentConsumers` / `maxConcurrentConsumers` / `prefetchCount`. Each per-service queue is therefore drained by a single consumer thread with default prefetch — a hard throughput ceiling per queue regardless of available cores. (Thread-safety itself is sound: the converter and strategies are stateless, `HandledEventTypesCache` memoization is correctly `synchronized`, and `compiledSchemaCache` uses `computeIfAbsent`.)
- Fix: Expose `concurrentConsumers`, `maxConcurrentConsumers`, and `prefetchCount` as configuration (defaulting e.g. 2–8 / bounded / prefetch ~ 10× consumers) and set them on the factory. Pairs with [JDK-001] for virtual-thread execution.

## 25. Code Quality

### [QUAL-001] [High] Demo "poison" endpoint shipped in the production REST surface, unguarded
- Location: `producer-service/.../controller/OrderController.java:119` (`publishPoison`, `POST /api/orders/poison`) — Module: producer-service
- Review: This endpoint bypasses the converter and publishes garbage bytes directly to the orders exchange with valid schema headers, intentionally forcing consumer failures onto the DLQ. It is protected only by a Javadoc comment ("FOR DEMO/TEST USE ONLY") — no profile guard, no auth. In production it is an unauthenticated DLQ-flooding / abuse vector reachable by anyone who can hit the service.
- Fix: Remove it from the production controller, or gate it behind `@Profile("demo")` (or a `@ConditionalOnProperty`) so it is never registered in prod, and require authentication. Move the poison-flow to an integration test where it belongs.

### [QUAL-002] [Low] Silent `long`→`int` truncation of retry TTL
- Location: `event-contract-kit/.../topology/EventTopologyFactory.java:24` (`serviceRetryQueue`, `.ttl((int) ttlMs)`) — Module: event-contract-kit
- Review: `ttlMs` is a `long` sourced from configuration but cast to `int` for `QueueBuilder.ttl`. A configured TTL above `Integer.MAX_VALUE` ms (~24.8 days) wraps to a nonsensical/negative value with no warning. Low likelihood, silent failure mode.
- Fix: Validate the tier value fits in an `int` (or clamp with a logged warning) before casting — `Math.toIntExact(ttlMs)` at minimum surfaces the overflow as an exception.

### [QUAL-003] [Low] Magic literal for the failure-message truncation length
- Location: `schema-messaging-core/.../consumer/EventConsumerSupport.java:66` (`populateFailureHeaders`, `truncate(root.getMessage(), 512)`) — Module: schema-messaging-core
- Review: The stack-trace cap is a named constant (`MAX_STACK_TRACE_BYTES`), but the sibling `512`-byte message cap is an inline literal, an inconsistency that invites drift.
- Fix: Extract a `MAX_FAILURE_MESSAGE_BYTES = 512` constant alongside the existing one.

## 28. Maintainability

### [MAINT-001] [Medium] Retry ladder is variable-length in the topology but hard-coded to exactly three tiers in config
- Location: `schema-messaging-core/.../config/RetryTierProperties.java` (record `tier0Ms, tier1Ms, tier2Ms`) vs. `event-contract-kit/.../TopologyNaming.java:96` (`tierSuffix` handles arbitrary `tier`) — Modules: schema-messaging-core, event-contract-kit
- Review: `TopologyNaming.tierSuffix` and `EventTopologyFactory` iterate `tierTtls.length` and even have a `default -> "t" + tier` branch for N tiers, but `RetryTierProperties` fixes exactly three fields bound from three separate `@Value`s. Adding or removing a tier means editing the record, its `toArray()`, and every `@Value` default — the abstraction promises N tiers the config can't express.
- Fix: Model tiers as an ordered collection — `@ConfigurationProperties` binding `events.retry.tiers` to a `List<Duration>` — and drive `toArray()` / declaration off that list (ties into [SPRING-002]).

### [MAINT-002] [Low] Legacy-queue decommission flag defaults on permanently
- Location: `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java:42` (`events.topology.decommission-legacy-queues:true`) — Module: schema-messaging-core
- Review: A one-time migration behavior (delete pre-per-service shared-domain queues) defaults to `true` forever, so every service boot re-issues `deleteQueue` for legacy names indefinitely. It is idempotent, but it's a migration step masquerading as steady-state config and risks silently deleting a queue if a legacy name is ever intentionally reintroduced.
- Fix: Document it as a migration toggle and flip the default to `false` post-migration (or remove once all environments are migrated), so steady-state boots don't carry migration side effects.

## 29. Scalability Assessment

Rough capacity read against the module's target tiers, given the current single-consumer default and blocking I/O:

- **100 concurrent / low throughput:** fine as-is.
- **1,000 concurrent:** the single consumer per queue ([CONC-001]) becomes the ceiling on any hot event type; publish-side has no backpressure signal ([SPRING-001]).
- **10,000+ / millions/day:** requires consumer concurrency + prefetch tuning, virtual threads, publisher confirms, and horizontal replicas — plus attention to the health/broker interactions below.

### [SCAL-001] [High] Consumer throughput is capped by the single-consumer default
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` (`rabbitListenerContainerFactory`) — Module: schema-messaging-core
- Review: With one consumer thread and default prefetch per per-service queue (see [CONC-001]), a hot event type cannot be scaled up within a single instance; the only lever is more pods. This is the primary throughput bottleneck for the 1k–10k tiers and compounds with the double-parse cost of [PERF-001].
- Fix: Make concurrency/prefetch configurable (per [CONC-001]) and load-test to size them; combine with virtual threads ([JDK-001]) and horizontal replicas.

### [SCAL-002] [Medium] A non-empty DLQ drives the whole service health to DOWN
- Location: `consumer-service/.../health/QueueDepthHealthIndicator.java` (`health`) — Module: consumer-service
- Review: `health()` returns `Health.down()` whenever any DLQ has messages. A `HealthIndicator` contributes to the composite `/actuator/health`; if that (or a health group containing it) is wired to a Kubernetes **liveness** probe, a DLQ backlog would restart the pod — killing the very consumer that needs to run to drain it — and to a **readiness** probe it would pull the pod from rotation. A data condition (poison messages) should not take the compute out of service.
- Fix: Keep DLQ depth as reported *detail/metric* and alert on it, but don't let it flip liveness/readiness. Explicitly assign this indicator to a non-liveness health group, and reserve DOWN for the consumer's own inability to function (broker unreachable) — which the probe-failure branch already models as UNKNOWN.

### [SCAL-003] [Medium] Health probe issues synchronous broker RPCs per event type on every call
- Location: `consumer-service/.../health/QueueDepthHealthIndicator.java` (`health` → `queueDepth`) — Module: consumer-service
- Review: Each `health()` call does two `rabbitAdmin.getQueueInfo(...)` round trips (main + DLQ) per handled event type. Under aggressive probe intervals × many event types × many replicas, this adds real management-channel load, and each probe blocks on the broker — a slow broker makes health checks slow exactly when the system is stressed.
- Fix: Cache queue depths with a short TTL (e.g. a few seconds) or sample on a scheduled task and serve the cached snapshot from `health()`; cap/batch the broker queries. Consider a lightweight liveness that never touches the broker, with depth reporting confined to a readiness/metrics surface.

---

## 4. Prioritized Fix List

1. [QUAL-001][High] Poison demo endpoint in prod REST surface — `producer-service/.../controller/OrderController.java` — remove or `@Profile("demo")` + auth-gate `POST /api/orders/poison`.
2. [SPRING-001][High] No publisher confirms/returns — `schema-messaging-core/.../publisher/EventPublisher.java` — enable correlated confirms + returns + mandatory, add callbacks so lost publishes surface.
3. [SCAL-001][High] Single-consumer throughput ceiling — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — make concurrency/prefetch configurable and load-test.
4. [SPRING-002][Medium] `@Value`-scattered retry/topology config — `schema-messaging-core/.../config/RetryTierPropertiesAutoConfiguration.java` — migrate to validated `@ConfigurationProperties`.
5. [JDK-001][Medium] Virtual threads not enabled on Java 25 — `*/application.yml` — set `spring.threads.virtual.enabled=true` and validate.
6. [PERF-001][Medium] Double JSON parse per message — `schema-messaging-core/.../serde/JsonSchemaStrategy.java` — parse once, validate the `JsonNode`, then convert.
7. [PERF-002][Medium] Startup `getBean()` over all definitions — `schema-messaging-core/.../consumer/BitsEventHandlerRegistrar.java` — resolve types without instantiating beans.
8. [CONC-001][Medium] Listener concurrency/prefetch at defaults — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — expose and set container concurrency/prefetch.
9. [MAINT-001][Medium] Retry tiers hard-coded to 3 — `schema-messaging-core/.../config/RetryTierProperties.java` — model tiers as a `List<Duration>`.
10. [SCAL-002][Medium] DLQ backlog drives service DOWN — `consumer-service/.../health/QueueDepthHealthIndicator.java` — keep DLQ depth as metric/alert, don't flip liveness/readiness.
11. [SCAL-003][Medium] Broker RPCs per health probe — `consumer-service/.../health/QueueDepthHealthIndicator.java` — cache/sample queue depths; keep liveness broker-free.
12. [ARCH-001][Low] Consumer infra active in producer — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — gate listener-only beans.
13. [ARCH-002][Low] `SchemaFileNaming` duplicated — `schema-messaging-core` + `schema-gen-tools` — no action while parity tests hold; revisit if a third copy appears.
14. [SPRING-003][Low] Field-injected `serviceName` — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java:49` — use bean-method parameter injection with a default.
15. [JDK-002][Low] `Collectors.toList()` → `Stream.toList()` — `schema-messaging-core/.../serde/JsonSchemaStrategy.java:119`.
16. [GC-001][Low] Allocation churn from double parse — `schema-messaging-core/.../serde/JsonSchemaStrategy.java` — resolved by [PERF-001]; keep G1.
17. [QUAL-002][Low] `long`→`int` TTL truncation — `event-contract-kit/.../topology/EventTopologyFactory.java:24` — `Math.toIntExact` / validate.
18. [QUAL-003][Low] Magic `512` truncation literal — `schema-messaging-core/.../consumer/EventConsumerSupport.java:66` — extract a constant.
19. [MAINT-002][Low] Decommission flag defaults on permanently — `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java:42` — flip default to `false` post-migration.

---

_Areas 4 (Data & API), 5 (Security & Resilience), 6 (Observability & Testing), and 7 (Ops, Cloud & Build) were not selected and were not assessed. Given SPRING-001, QUAL-001, and the DLQ/health interactions, a dedicated Security & Resilience pass is recommended before any production promotion._
