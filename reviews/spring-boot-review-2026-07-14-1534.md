# Spring Boot Production Readiness Review

**Project:** schema-registry-demo
**Date:** 2026-07-14 15:34
**Reviewer framing:** Principal Software Architect, JVM Performance Engineer, Spring Boot Expert, Security Architect, Distributed Systems Expert, Production Reliability Engineer

---

## 1. Stack Summary

| Attribute | Value |
|---|---|
| Spring Boot version | 4.1.0 |
| JDK version | 25 (enforced via `maven-toolchains-plugin` + `maven-compiler-plugin release=25`) |
| Language(s) | Java only |
| Build tool | Maven (`./mvnw`) |
| Layout | Multi-module (7 modules): `schema-messaging-core`, `event-contract-kit`, `order-contracts`, `customer-contracts`, `producer-service`, `consumer-service`, `schema-gen-tools` |
| Run mode | Parallel subagents (one per selected area) |
| Areas run | 1. Architecture & Design · 2. Spring Boot & JDK · 3. Performance & Runtime · 8. Quality & Scalability |
| Areas skipped | 4. Data & API · 5. Security & Resilience · 6. Observability & Testing · 7. Ops, Cloud & Build |

This is an Apicurio Registry (schema governance) + RabbitMQ (transport) + Spring Boot messaging POC. It has **no JPA entities or repositories anywhere** — it is a pure event-driven messaging system, not a persistence app, so classic database-scaling analysis does not apply. Note that Security & Resilience (area 5) and Observability & Testing (area 6) were **not selected** for this run — several findings below (e.g. missing publisher confirms, no auth on a bypass endpoint) touch those areas incidentally but a dedicated pass was out of scope.

---

## 2. Executive Summary

The project is well-architected for a POC — clean module boundaries, machine-enforced dependency rules (`core ↛ contracts`), a documented and self-consistent exception taxonomy, and thoughtful fail-fast schema loading. However, the reviewed areas surfaced **7 High-severity findings**, concentrated in three risk clusters: (1) the RabbitMQ producer/consumer path gives no delivery guarantees despite documented "at-least-once" semantics — no publisher confirms exist, so publish failures are silently swallowed; (2) consumer throughput is hard-capped at one thread per queue because the custom listener container factory bypasses Spring Boot's standard configuration binding; and (3) a schema-validation-bypassing demo endpoint (`/api/orders/poison`) is deployed unauthenticated in the production controller. None of these are Critical (no data corruption or active exploit was confirmed), but all are the kind of gap that turns into an incident under real load or in a non-POC deployment.

**Go / No-Go verdict: No-Go for production as-is.** Driven by the 7 open High-severity findings — most critically the missing publisher confirms (silent message loss) and the unbounded/untunable consumer concurrency (hard throughput ceiling) — both are scoped, well-understood fixes, not architectural rewrites.

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 7 |
| Medium | 22 |
| Low | 22 |

---

## 3. Findings

## 1. Overall Architecture Review

### [ARCH-001] [High] Demo bypass-validation endpoint lives in the production controller
- Location: `producer-service/src/main/java/com/example/producer/controller/OrderController.java` (`OrderController#publishPoison`) — Module: producer-service
- Review: `POST /api/orders/poison` is wired into the same `@RestController` as every real order endpoint, is unauthenticated, and injects hand-crafted malformed bytes directly onto the production exchange via a raw `RabbitTemplate` the controller holds in addition to `EventPublisher`. It deliberately bypasses the one validation authority the whole architecture is built around (`SchemaAwareMessageConverter`). Any caller who can reach the service can trigger DLQ floods and `X-Failure-*` noise on demand. (Related: see SB-005 for the same finding from a Spring-practice/security angle.)
- Fix: Move this to a `@Profile("demo")`-gated bean, a separate test-only controller not packaged in the production jar, or at minimum secure it behind an admin-only path/feature flag and drop the direct `RabbitTemplate` dependency from the controller.

### [ARCH-002] [Medium] Every service blanket-depends on every domain's contracts module
- Location: `producer-service/pom.xml`, `consumer-service/pom.xml` — Module: producer-service, consumer-service
- Review: Both services pull in every `*-contracts` module regardless of which events they actually publish/handle. `LocalSchemaCatalog` then eagerly loads and `warm()`s every `TypeMapping` visible on the classpath at startup, and each domain's `*TopologyAutoConfiguration` unconditionally declares all three exchanges present on the classpath. There is no mechanism for a service to depend on a subset of a domain's events. For 2 domains/7 events this is cheap; it does not scale linearly with system size.
- Fix: Accept as a deliberate POC-scale tradeoff (document it), or introduce per-event opt-in so a service declares which `TypeMapping` beans it wants rather than "whatever is on the classpath."

### [ARCH-003] [Medium] Producer's exception handling doesn't cover the full failure surface of the path it fronts
- Location: `producer-service/src/main/java/com/example/producer/controller/GlobalExceptionHandler.java` (only `@ExceptionHandler(SchemaValidationException.class)`) vs. `schema-messaging-core/.../converter/SchemaAwareMessageConverter.java` and `.../publisher/EventPublisher.java` — Module: producer-service, schema-messaging-core
- Review: `EventPublisher.publish()` can throw `IllegalStateException` (no `TypeMapping` registered) and `SchemaAwareMessageConverter.toMessage()` can throw `MessageConversionException` or `SerializationException` (a sibling of `SchemaValidationException`, not caught by the current handler). None of these are mapped by `GlobalExceptionHandler`, so they fall through to Spring Boot's default 500 handler. (Related: QUAL-004 covers the same gap plus broker-connectivity failures.)
- Fix: Catch the `SchemaMessagingException` base type (covers `SerializationException` too) and add a handler for `MessageConversionException`/`IllegalStateException` from the publish path, each mapped to an appropriate 4xx/5xx.

### [ARCH-004] [Low] No REST API versioning strategy
- Location: `producer-service/src/main/java/com/example/producer/controller/OrderController.java`, `CustomerController.java` (`@RequestMapping("/api/orders")`, `@RequestMapping("/api/customers")`) — Module: producer-service
- Review: The project invests heavily in governed, versioned schema evolution (Apicurio FORWARD compatibility) but the REST surface that produces those events has no version prefix or content-negotiation scheme.
- Fix: Adopt a versioning convention (`/api/v1/orders`) now, before external callers exist, so it's free rather than a later breaking migration.

## 2. Multi-Module Structure

### [ARCH-005] [Medium] `event-contract-kit` bundles two unrelated concerns under one package
- Location: `event-contract-kit/src/main/java/com/example/amqp/topology/` (`TopologyNaming.java`, `EventTopologyFactory.java`) vs. `.../topology/mapping/` (`TypeMapping.java`, `SchemaCoordinates.java`, `SchemaType.java`) — Module: event-contract-kit
- Review: AMQP naming/topology-building and schema-registry identity value types have different reasons to change, yet both live under `com.example.amqp.topology`, with the schema-identity types nested inside what is nominally an "amqp topology" package. `TypeMapping.java`'s own Javadoc has to explain "since ADR-0006" — a sign the boundary followed a dependency-graph convenience decision rather than cohesion.
- Fix: Split into two top-level packages (e.g. `com.example.eventcontracts.topology` and `com.example.eventcontracts.mapping`), or rename the module/package to something domain-neutral.

### [ARCH-006] [Medium] Per-domain auto-configuration is hand-duplicated with no shared abstraction
- Location: `order-contracts/.../topology/OrderTopologyAutoConfiguration.java` + `OrderTypeMappingAutoConfiguration.java` vs. `customer-contracts/.../topology/CustomerTopologyAutoConfiguration.java` + `CustomerTypeMappingAutoConfiguration.java` — Module: order-contracts, customer-contracts
- Review: The two domain modules are structurally identical: three `TopicExchange` beans on the same `main`/`.dlx`/`.retry.exchange` pattern, and one `TypeMapping` bean per event on the same pattern. Onboarding a third domain means copy-pasting ~65 lines with only names changed, and the two existing copies can silently drift. (Related: QUAL-002 reaches a more lenient conclusion on the same duplication, judging it low-risk given the project's explicit domain-isolation goal — both perspectives are preserved here.)
- Fix: Add a small builder/factory in `event-contract-kit`, e.g. `DomainTopology.of(exchangeBaseName)` and `TypeMapping.forEvent(...)`, so each domain module declares a short, data-only list instead of re-implementing the bean-wiring pattern.

### [ARCH-007] [Low] `RabbitAdmin` bean placement doesn't match its class name/scope
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingConsumerAutoConfiguration.java` — Module: schema-messaging-core
- Review: The class is named `SchemaMessagingConsumerAutoConfiguration` but declares the always-on `RabbitAdmin` bean a pure producer needs too. A reader scanning for where `RabbitAdmin` comes from in a producer-only service would reasonably skip a class named "Consumer" auto-configuration.
- Fix: Rename the outer class (e.g. `AmqpInfrastructureAutoConfiguration`) or move the always-on beans into `SchemaMessagingAutoConfiguration`, keeping only listener-gated beans in a consumer-named class.

## 3. Spring Boot Best Practices

### [SB-001] [High] Unconditional legacy-queue deletion on every startup can lose in-flight messages during rolling deploys
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java` (`ServiceQueueTopologyConfigurer#decommissionLegacySharedDomainQueues`) — Module: schema-messaging-core
- Review: `rabbitAdmin.deleteQueue(queueName)` deletes unconditionally — it does not use the `ifUnused`/`ifEmpty` overload. This runs by default on every startup (`events.topology.decommission-legacy-queues` defaults to `true`). During a rolling deploy where an old instance is still publishing/consuming against the legacy queue while a new instance boots and deletes it, any messages still queued there are permanently lost with no warning. (Related: ARCH-008, QUAL-005, QUAL-006 cover the SRP/maintainability/overhead angles of this same method.)
- Fix: Use `channel.queueDelete(queueName, false, true)` (ifUnused=false, ifEmpty=true) so a non-empty legacy queue is skipped/logged instead of silently destroyed, or gate this behind a one-time migration job/flag rather than every boot.

### [SB-002] [High] Publishing gives false success — no publisher confirms/returns, so unroutable or dropped messages are invisible to the caller
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/publisher/EventPublisher.java` (`EventPublisher#publish`) — Module: schema-messaging-core, producer-service
- Review: `EventPublisher.publish()` calls `rabbitTemplate.send(...)` with no `publisher-confirm-type`, no `publisher-returns`, no `mandatory` flag, and no `ConfirmCallback`/`ReturnsCallback`. `OrderController`/`CustomerController` return HTTP 201 as soon as `send()` returns locally. If the exchange has no matching binding or the broker rejects the message, it is silently dropped and the caller never finds out. (Related: QUAL-008 reaches the same finding from the scalability/at-least-once-guarantee angle.)
- Fix: Enable `spring.rabbitmq.publisher-confirm-type=correlated` and `spring.rabbitmq.publisher-returns=true`, set `mandatory=true`, and wire a `ConfirmCallback`/`ReturnsCallback` so publish failures surface as errors instead of phantom 201s.

### [SB-003] [High] Custom `SimpleRabbitListenerContainerFactory` bypasses Spring Boot's `SimpleRabbitListenerContainerFactoryConfigurer` — standard `spring.rabbitmq.listener.*` properties are silently ignored
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration#rabbitListenerContainerFactory`) — Module: schema-messaging-core
- Review: The factory is built with `new SimpleRabbitListenerContainerFactory()` directly, configured only with `connectionFactory`, `messageConverter`, `defaultRequeueRejected`, and the advice chain — it never goes through Boot's auto-configured `SimpleRabbitListenerContainerFactoryConfigurer`, which is what binds `spring.rabbitmq.listener.simple.concurrency`, `max-concurrency`, `prefetch`, `retry.*`. Neither `application.yml` sets these, so the container runs at the hard-coded default of one consumer thread per queue with no config-driven way to scale. (Related: PERF-003 and QUAL-007 quantify the throughput impact of this same gap.)
- Fix: Inject `SimpleRabbitListenerContainerFactoryConfigurer` and call `configurer.configure(factory, connectionFactory)` before applying the custom converter/advice-chain, then set concurrency/prefetch via `spring.rabbitmq.listener.simple.*`.

### [SB-004] [Medium] Retry-ladder TTLs bound via raw `@Value`, not `@ConfigurationProperties` — no validation, no relaxed binding
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/RetryTierPropertiesAutoConfiguration.java` — Module: schema-messaging-core
- Review: `events.retry.tier{0,1,2}.ms` is bound with three separate `@Value("${...:default}")` parameters instead of a validated `@ConfigurationProperties` type. There is no validation — a misconfigured `events.retry.tier0.ms=0` or negative value is accepted silently and would corrupt the TTL retry ladder at runtime. `schema-messaging-core/pom.xml` already pulls in `spring-boot-configuration-processor`, suggesting `@ConfigurationProperties` was the intended pattern but wasn't followed through (see SB-008).
- Fix: Convert to a validated `@ConfigurationProperties("events.retry")` record with `@Positive`/an ascending-order invariant, registered via `@EnableConfigurationProperties`.

### [SB-005] [Medium] `/api/orders/poison` bypass-validation endpoint is permanently live with no profile gating or auth
- Location: `producer-service/src/main/java/com/example/producer/controller/OrderController.java` (`OrderController#publishPoison`) — Module: producer-service
- Review: Same endpoint as ARCH-001, reviewed here from the Spring-configuration angle: it's explicitly documented as "FOR DEMO/TEST USE ONLY" but is mapped unconditionally with no `@Profile`, feature flag, or authentication.
- Fix: Gate behind `@Profile("demo")`/`@ConditionalOnProperty` on a separate `@RestController`, or remove before any non-POC deployment.

### [SB-006] [Low] Field injection used in one auto-configuration class, inconsistent with constructor injection elsewhere
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration`, `@Value("${spring.application.name}") private String serviceName;`) — Module: schema-messaging-core
- Review: Every other `@Value` binding in the codebase is injected via constructor or `@Bean`-method parameter (final, testable). This one class uses mutable field injection instead.
- Fix: Move `serviceName` to a constructor parameter on `ListenerConfiguration` and drop the field-level `@Value`.

### [SB-007] [Low] Identical `NoBeanValidationWebMvcConfiguration` duplicated verbatim across two modules
- Location: `producer-service/src/main/java/com/example/producer/config/NoBeanValidationWebMvcConfiguration.java` and `consumer-service/src/main/java/com/example/consumer/config/NoBeanValidationWebMvcConfiguration.java` — Module: producer-service, consumer-service
- Review: Byte-for-byte the same class exists in both services. `consumer-service` has no `@RequestBody`-validated controllers, so it's unclear this is doing useful work there beyond what the `ValidationAutoConfiguration` exclusion already covers.
- Fix: Hoist into a shared module if genuinely needed in both, otherwise remove from `consumer-service`.

### [SB-008] [Low] `spring-boot-configuration-processor` dependency has nothing to process
- Location: `schema-messaging-core/pom.xml` — Module: schema-messaging-core
- Review: Generates `spring-configuration-metadata.json` from `@ConfigurationProperties` classes, but the module has zero such types (`RetryTierProperties` uses `@Value` instead — see SB-004). Currently dead weight.
- Fix: Adopt `@ConfigurationProperties` for `RetryTierProperties` (SB-004) to get real value from this dependency, or remove it.

## 4. JDK Best Practices

### [JDK-001] [Medium] JDK 25 is pinned but Virtual Threads are never enabled despite a blocking, listener-per-thread architecture
- Location: `producer-service/src/main/resources/application.yml`, `consumer-service/src/main/resources/application.yml` (no `spring.threads.virtual.enabled` anywhere) — Module: producer-service, consumer-service
- Review: The stack is deliberately on JDK 25, and the consumer's dominant runtime cost is blocking I/O per message (schema validation, Jackson (de)serialization, AMQP channel I/O) — exactly the workload virtual threads target. Combined with SB-003, the project is missing both the config flag and the wiring that would let Boot apply a virtual-thread executor to listener containers.
- Fix: Set `spring.threads.virtual.enabled=true` in both `application.yml`s, and fix SB-003 first (raising listener concurrency) so there's actually more concurrent work for virtual threads to help with.

### [JDK-002] [Low] Cause-chain walk in `classify()` uses manual `instanceof` looping instead of a sealed-type pattern-matching `switch`
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/EventConsumerSupport.java` (`EventConsumerSupport#classify`) — Module: schema-messaging-core
- Review: The exception taxonomy is documented as "table-driven" and self-classifying via the `PermanentFailure` marker interface — exactly the shape JDK 21+ sealed-type pattern matching targets. Today it's a `while` loop with `instanceof` checks; not wrong, but leaves the documented "closed taxonomy" intent without compiler-checked exhaustiveness.
- Fix (optional modernization): Consider sealing `PermanentFailure`'s permitted implementations purely for documentation/compile-time enforcement; the existing `instanceof` check in `classify()` remains correct regardless (it must stay because `MessageConversionException` is a foreign Spring type).

### [JDK-003] [Low] Domain records skip compact-constructor invariant checks
- Location: `event-contract-kit/src/main/java/com/example/amqp/topology/mapping/SchemaCoordinates.java`, `TypeMapping.java`, `schema-messaging-core/.../config/RetryTierProperties.java` — Module: event-contract-kit, schema-messaging-core
- Review: These records are used as map keys and as the runtime source of truth for routing/schema identity or retry TTLs, yet none has a compact constructor guarding non-null/positive invariants. A caller constructing one directly (a future domain, a test) can silently produce a broken mapping (`"null.consumer-service.queue"`) instead of failing fast.
- Fix: Add compact constructors with `Objects.requireNonNull`/`@Positive`-equivalent checks on `SchemaCoordinates`, `TypeMapping`, and `RetryTierProperties`.

## 5. SOLID Principles

### [ARCH-008] [Medium] SRP — steady-state topology declaration is permanently coupled to time-boxed migration cleanup
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java` (`ServiceQueueTopologyConfigurer#afterSingletonsInstantiated`, `#decommissionLegacySharedDomainQueues`) — Module: schema-messaging-core
- Review: The class's stated job is declaring per-service queues/DLQs/retry ladders (permanent, steady-state). But it also unconditionally deletes legacy shared-domain queues on every startup, with no expiry, version gate, or removal plan — an indefinite hidden side-effect with nothing to do with the class's primary job. (Related: SB-001 covers the data-loss risk this creates; QUAL-005/QUAL-006 cover the maintainability/overhead angle.)
- Fix: Extract `decommissionLegacySharedDomainQueues` into its own independently toggleable, independently removable `SmartInitializingSingleton`.

### [ARCH-009] [Medium] OCP — no extension point for onboarding a new event domain
- Location: Same as ARCH-006 (`order-contracts`/`customer-contracts` `topology/*AutoConfiguration.java` pairs) — Module: order-contracts, customer-contracts, event-contract-kit
- Review: The system is closed for extension at the domain level — a new domain must be added by copying two existing `@AutoConfiguration` classes and editing the copy, since nothing in `event-contract-kit` defines a domain's "shape" (exchange triple + event-to-mapping list) as data or an SPI.
- Fix: Same as ARCH-006 — a `DomainEventDescriptor`-style value type/factory in `event-contract-kit` turns "add a domain" into "add data."

### [ARCH-010] [Low] SRP — `SchemaAwareMessageConverter` concentrates five responsibilities in one class
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/converter/SchemaAwareMessageConverter.java` (`toMessage`, `fromMessage`) — Module: schema-messaging-core
- Review: A single class does `TypeMapping` resolution, header extraction/validation, schema lookup, `SerializationStrategy` dispatch, and exception translation/logging for both directions. Internally consistent and well-tested, but the largest single point of coupling in the core library.
- Fix: Not urgent given current test coverage; if the header/validation surface grows, extract a `SchemaHeaderReader` and let the converter orchestrate.

## 6. Object-Oriented Design

### [ARCH-011] [Low] Event-defaulting logic lives in the controller, not on the domain record
- Location: `producer-service/src/main/java/com/example/producer/controller/OrderController.java` (`createOrder`), `CustomerController.java` (`registerCustomer`) — Module: producer-service
- Review: `OrderCreated`/`CustomerRegistered` are correctly immutable records, but the logic for constructing a valid instance from partial input (`UUID.randomUUID()`, `Instant.now()`) lives entirely in the web-layer controllers. If a second producer of these events is ever added, the rule must be re-implemented.
- Fix: Add a static factory, e.g. `OrderCreated.newOrder(customerId, productId, quantity, totalAmount, currency)`, and have the controller call it.

### [ARCH-012] [Low] Heavy reliance on static utility classes limits per-service customization
- Location: `event-contract-kit/src/main/java/com/example/amqp/topology/TopologyNaming.java`, `EventTopologyFactory.java`; `schema-messaging-core/.../consumer/BitsEventHandlerScanner.java` — Module: event-contract-kit, schema-messaging-core
- Review: The naming-convention and topology-building algorithms are hardcoded static methods with no interface behind them — fine for a single-convention POC, but not substitutable via DI/composition if a second naming convention is ever needed.
- Fix: Not worth doing now (YAGNI is defensible); if a second convention is needed, wrap `TopologyNaming` behind an injectable `NamingStrategy` interface.

## 7. Design Patterns

### [ARCH-013] [Medium] Missing backpressure/circuit-breaker protection on the failure-routing path
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/DlxMessageRecoverer.java` (`recover`), invoked from `DlxRoutingAdvice.invoke` — Module: schema-messaging-core
- Review: Every failed message triggers a synchronous `rabbitTemplate.send(...)` to the retry/DLX exchange, on the same listener thread, with no circuit breaker or timeout around the routing call itself. During a broker blip coinciding with an error storm, every listener thread blocks/throws on this call in a tight loop with no protection.
- Fix: Wrap the `rabbitTemplate.send` in `recover()` with a bounded retry/circuit breaker (e.g. Resilience4j) so a broker outage degrades gracefully.

### [ARCH-014] [Low] Missing Factory/Template Method for domain onboarding
- Location: Same as ARCH-006/ARCH-009 — Module: order-contracts, customer-contracts, event-contract-kit
- Review: From a patterns lens, this is a textbook Factory Method / Template Method case: two structurally-identical `@AutoConfiguration` pairs differing only in data. No such factory exists.
- Fix: See ARCH-006's fix — a small declarative factory in `event-contract-kit` collapses both copies into data.

## 8. Performance Review

### [PERF-001] [High] Unconditional per-message INFO logging on the produce hot path
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/converter/SchemaAwareMessageConverter.java` (`toMessage`), `schema-messaging-core/.../publisher/EventPublisher.java` (`publish`) — Module: schema-messaging-core
- Review: Every produced message triggers two `log.info(...)` calls, each doing SLF4J parameter formatting and synchronous I/O by default. This runs on the throughput-sensitive produce path for every message with no sampling or level gating — under load, logging becomes the bottleneck rather than the broker or serialization.
- Fix: Downgrade to `log.debug`, or gate behind `log.isDebugEnabled()`; if INFO-level publish visibility is required, emit a metric/counter instead of a log line per message.

### [PERF-002] [Medium] Triplicated per-message logging across producer and consumer call chains
- Location: `producer-service/.../OrderController.java`, `CustomerController.java`; `consumer-service/.../listener/OrderEventListener.java`, `CustomerEventListener.java` — Module: producer-service, consumer-service
- Review: Combined with PERF-001, a single published event produces 3 INFO log lines on the producer side (controller + `EventPublisher` + `SchemaAwareMessageConverter`) and another on the listener side. At any meaningful message rate this multiplies log volume and I/O contention.
- Fix: Consolidate to one log statement per logical operation at DEBUG, and rely on the existing `X-Correlation-Id` header plus metrics for traceability.

### [PERF-003] [Medium] No RabbitMQ listener concurrency or prefetch tuning
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration#rabbitListenerContainerFactory`); both `application.yml` files — Module: schema-messaging-core, consumer-service
- Review: No `setConcurrentConsumers`/`setMaxConcurrentConsumers`/`setPrefetchCount` is set anywhere, and neither `application.yml` sets `spring.rabbitmq.listener.simple.*`. With 7 per-service queues, that's a hard ceiling of one thread processing each queue serially, with no scaling knob. (Same root cause as SB-003 and QUAL-007.)
- Fix: Externalize concurrency/prefetch via `spring.rabbitmq.listener.simple.concurrency`/`max-concurrency`/`prefetch`, sized per expected throughput and load-tested.

### [PERF-004] [Low] LocalSchemaCatalog eager-load/compile-all tradeoff (informational)
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/schema/LocalSchemaCatalog.java`; `SchemaAwareMessageConverter` constructor `warm()` loop — Module: schema-messaging-core
- Review: At startup, every `TypeMapping`'s schema is compiled synchronously on the main thread. Negligible for 7 event types today; a reasonable fail-fast tradeoff, but will grow linearly with no parallelism if the catalog scales to hundreds of event types.
- Fix: No change needed at current scale. If the catalog grows substantially, parallelize the `warm()`/load loop.

### [PERF-005] [Low] RabbitMQ ConnectionFactory / channel cache left at framework defaults
- Location: `producer-service/src/main/resources/application.yml`, `consumer-service/src/main/resources/application.yml` — Module: producer-service, consumer-service
- Review: No `CachingConnectionFactory` tuning is present. Spring Boot's default channel cache size (25) may be insufficient under concurrent publish bursts from MVC request threads (default Tomcat pool of 200), causing channel checkout contention under load.
- Fix: Set `spring.rabbitmq.cache.channel.size` based on measured concurrent-publish load — this needs a load test to size correctly.

## 9. Memory Analysis

### [PERF-006] [Low] Full (untruncated) stack trace materialized before truncation on every failure
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/EventConsumerSupport.java` (`populateFailureHeaders`, `stackTrace`, `truncate`) — Module: schema-messaging-core
- Review: `stackTrace(cause)` builds the entire exception stack trace into a `String` (unbounded) before `truncate()` cuts it to 4KB. Under a sustained failure storm, each failed message allocates a full-size string only to discard most of it.
- Fix: Cap trace depth while writing instead of serializing the whole chain and truncating afterward. Low priority since this only fires on the failure path.

### [PERF-007] [Low] No memory leak risks identified in caches/registries — confirmed
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/mapping/TypeMappingRegistry.java`, `.../schema/LocalSchemaCatalog.java`, `.../serde/JsonSchemaStrategy.java` — Module: schema-messaging-core
- Review: `TypeMappingRegistry` and `LocalSchemaCatalog` are immutable (`Map.copyOf`), fixed-size, built once. `JsonSchemaStrategy.compiledSchemaCache` is a `ConcurrentHashMap` populated only during startup `warm()` from a fixed, small key set — it never grows unboundedly at runtime. No static mutable collections, ThreadLocals, or unbounded caches were found.
- Fix: None required. Positive confirmation, not an action item.

## 10. Garbage Collection Review

### [PERF-008] [Medium] No explicit GC algorithm or heap sizing configured for a message-broker workload
- Location: `consumer-service/Dockerfile`, `producer-service/Dockerfile` (`ENTRYPOINT ["java", "-jar", "app.jar"]`, no `-XX` flags); `docker-compose.yml` (no memory `limits`) — Module: consumer-service, producer-service
- Review: Both Dockerfiles launch the JVM with zero tuning flags. JDK 25's default G1 is a reasonable fit for this workload's short-lived-allocation profile, but with no container memory limits set, JVM ergonomics (`MaxRAMPercentage`) will size the heap off the host's/container's visible memory rather than an intentional budget — a real risk if this is carried into a resource-constrained production deployment (OOM-kill or over-collection).
- Fix: Add explicit `-XX:MaxRAMPercentage=<N>` (or `-Xmx`) and container memory `limits` in whatever orchestrator manifest replaces `docker-compose.yml` for production; G1 itself needs no change.

### [PERF-009] [Low] Per-message JsonNode tree churn is inherent to the design, not a defect
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/serde/JsonSchemaStrategy.java` (`serialize`, `deserialize`) — Module: schema-messaging-core
- Review: Produce/consume both build a short-lived `JsonNode` tree, validate it, then serialize/convert. This is escape-analysis-friendly object churn appropriate for G1's young-gen collection, and the class deliberately parses/serializes the payload only once.
- Fix: None required at current message sizes/rates; re-measure with actual GC logs if payload sizes or throughput increase substantially.

## 11. Concurrency Review

### [PERF-010] [Medium] Virtual threads (JDK 25) not enabled anywhere despite blocking I/O throughout the stack
- Location: `consumer-service/src/main/resources/application.yml`, `producer-service/src/main/resources/application.yml`; `SchemaMessagingConsumerAutoConfiguration` listener container factory — Module: consumer-service, producer-service, schema-messaging-core
- Review: The project is explicitly built on JDK 25, but `spring.threads.virtual.enabled` is never set, and the listener container factory uses the framework's default platform-thread executor. Listener invocation involves blocking work (schema validation, Jackson (de)serialization, AMQP I/O) — exactly what virtual threads target. (Same finding as JDK-001.)
- Fix: Set `spring.threads.virtual.enabled=true` for both services; this is complementary to, not a substitute for, fixing listener concurrency (PERF-003/SB-003) — enabling the flag alone with `concurrency=1` won't move the needle.

### [PERF-011] [Low] Health probe issues N sequential blocking broker RPCs per invocation
- Location: `consumer-service/src/main/java/com/example/consumer/health/QueueDepthHealthIndicator.java` (`health`, `queueDepth`) — Module: consumer-service
- Review: `health()` calls `rabbitAdmin.getQueueInfo(...)` twice per handled event type (main + DLQ) — up to 14 blocking round-trips per `/actuator/health` call today, sequential and synchronous. (Related: QUAL-009 covers the same finding from a liveness/readiness-probe-timeout angle.)
- Fix: At current scale (7 types) this is a non-issue; if the handled-type count grows, parallelize the probes and/or cache queue depth with a short TTL.

### [PERF-012] [Low] Confirmed thread-safe: shared caches/registries under concurrent listener threads
- Location: `TypeMappingRegistry.java`, `LocalSchemaCatalog.java`, `JsonSchemaStrategy.java`, `HandledEventTypesCache.java` — Module: schema-messaging-core
- Review: Every shared structure read from multiple concurrent listener threads is either immutable before any listener starts, or backed by `ConcurrentHashMap`/`synchronized` with idempotent recomputation. No race conditions or unsynchronized mutable shared state were found.
- Fix: None required. Positive confirmation, not an action item.

## 25. Code Quality

### [QUAL-004] [Medium] GlobalExceptionHandler only maps SchemaValidationException — other publish-time failures fall through to default error handling
- Location: `producer-service/src/main/java/com/example/producer/controller/GlobalExceptionHandler.java` — Module: producer-service
- Review: `EventPublisher.publish()` can throw `IllegalStateException` (no `TypeMapping` for the event class) or propagate `AmqpException`/`AmqpConnectException` when the broker is unreachable. None are handled, so callers get a generic 500 instead of a diagnosable, correctly-coded response. (Same underlying gap as ARCH-003, viewed here with the added broker-connectivity angle.)
- Fix: Add handlers for `AmqpException`/connection failures (→ 503) and consider whether a missing `TypeMapping` should be a distinct internal-config-error response.

### [QUAL-001] [Low] Byte-count truncation can split a multi-byte UTF-8 character
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/EventConsumerSupport.java` (`truncate`) — Module: schema-messaging-core
- Review: `truncate(String s, int maxBytes)` slices at a raw byte offset (`new String(bytes, 0, maxBytes, UTF_8)`). If a multi-byte UTF-8 character straddles the boundary, the cut produces a mangled trailing character in the `X-Failure-Message`/`X-Failure-StackTrace` DLQ headers.
- Fix: Decode defensively (e.g. `CharsetDecoder` with `CodingErrorAction.IGNORE`), or truncate on a char boundary first and re-check byte length in a loop.

### [QUAL-002] [Low] Boilerplate duplication between order-contracts and customer-contracts topology/mapping autoconfig
- Location: `order-contracts/.../topology/OrderTopologyAutoConfiguration.java`, `OrderTypeMappingAutoConfiguration.java` vs. `customer-contracts/.../topology/CustomerTopologyAutoConfiguration.java`, `CustomerTypeMappingAutoConfiguration.java` — Module: order-contracts, customer-contracts
- Review: Structurally identical, but largely justified by the project's explicit "contracts ↔ core is zero dependency" domain-isolation goal — the actually-shared logic (naming, retry-ladder building) is already correctly factored into `event-contract-kit`. What remains is thin, low-risk boilerplate. (See ARCH-006 for the stricter framing of this same duplication.)
- Fix: Optional — a tiny helper in `event-contract-kit` could cut ~10 lines per domain; not worth doing purely for 2 domains, revisit if a third domain is added.

### [QUAL-003] [Low] Inconsistent request-DTO mapping pattern between OrderController and CustomerController
- Location: `producer-service/.../OrderController.java` (nested request records mapped to domain records) vs. `CustomerController.java` (`AddAddressRequest` embeds the domain `Address` record directly) — Module: producer-service
- Review: Two controllers in the same service take two different design approaches to the same problem, increasing cognitive load for anyone adding an eighth event type.
- Fix: Pick one convention project-wide and document it in the controller package or CLAUDE.md.

## 28. Maintainability

### [QUAL-005] [Medium] `decommission-legacy-queues` flag is a permanent-looking migration toggle with no sunset mechanism
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/ServiceQueueTopologyAutoConfiguration.java` (`decommissionLegacySharedDomainQueues`), default `true` — Module: schema-messaging-core
- Review: The flag defaults to `true` forever — nothing forces this migration code to eventually be deleted once all environments have migrated off legacy queues. Every service pays a startup cost for this indefinitely. (Related: SB-001, ARCH-008, QUAL-006.)
- Fix: Track migration completion explicitly (a tracking issue/ADR with a target removal release) and delete the flag/method/`legacySharedDomainQueueNames` in one cleanup PR once confirmed.

### [QUAL-006] [Low] Legacy-queue decommission re-runs redundantly on every instance of every rolling deploy
- Location: Same as QUAL-005 (`decommissionLegacySharedDomainQueues` → `afterSingletonsInstantiated`) — Module: schema-messaging-core
- Review: Runs unconditionally on every instance's every startup for as long as the flag is `true`. Once legacy queues are actually gone, every replica in a fleet still issues no-op admin RPCs on every boot/scale-out — permanent, compounding overhead with no functional benefit.
- Fix: Same remediation as QUAL-005; alternatively gate behind a one-shot marker so it self-disables once it detects the legacy queues are already gone.

## 29. Scalability Assessment

### [QUAL-007] [High] No RabbitMQ listener concurrency or prefetch tuning — consumer throughput capped at effectively one thread per queue per instance
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration#rabbitListenerContainerFactory`) — Module: schema-messaging-core
- Review: Verified no `concurrentConsumers`/`prefetch` string appears anywhere in `*.java`/`*.yml`. Spring AMQP's default `concurrentConsumers` is 1, so each of the 7 per-service queues is drained by exactly one thread per `consumer-service` instance. At 10,000–100,000 req/day+ volumes this becomes the dominant bottleneck long before RabbitMQ itself does, and horizontal scaling is the only lever with no config guidance. (Same root cause as SB-003/PERF-003.)
- Fix: Externalize concurrency/prefetch as `@ConfigurationProperties`, with sane defaults (e.g. `concurrentConsumers=3-5`), and document the per-instance vs. per-replica scaling story.

### [QUAL-008] [High] No publisher confirms/returns — the documented "at-least-once" guarantee is not actually enforced at the network layer
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/publisher/EventPublisher.java` (`publish`); `producer-service/src/main/resources/application.yml` — Module: schema-messaging-core, producer-service
- Review: Verified neither `publisher-confirm-type` nor `publisher-returns` appears in either `application.yml`, nor is any `ConfirmCallback`/`ReturnCallback` registered. `rabbitTemplate.send()` returns as soon as the client writes the frame to the socket — a broker-side rejection, an unroutable message, or a dropped connection immediately after write is silently lost, undermining CLAUDE.md's documented "at-least-once" property. (Same finding as SB-002.)
- Fix: Enable `spring.rabbitmq.publisher-confirm-type=correlated` and `publisher-returns=true`, set `mandatory=true`, and wire callbacks in `EventPublisher` so publish failures are observable rather than silent.

### [QUAL-009] [Medium] QueueDepthHealthIndicator issues synchronous, untimeoutted AMQP RPCs on every /actuator/health call
- Location: `consumer-service/src/main/java/com/example/consumer/health/QueueDepthHealthIndicator.java` (`health`) — Module: consumer-service
- Review: 14 synchronous AMQP RPCs per health check today, no timeout configured. If wired as a Kubernetes liveness probe, a struggling-but-recoverable broker can cause healthy pods to be killed and restarted repeatedly — worsening the outage instead of isolating it. (Related: PERF-011.)
- Fix: Add an explicit timeout, move this check to readiness only (never liveness), and consider caching queue depth with a short TTL.

### [QUAL-010] [Medium] Health DOWN on any single DLQ message conflates message-level failure with instance health
- Location: `consumer-service/src/main/java/com/example/consumer/health/QueueDepthHealthIndicator.java` (`health`, `dlqEmpty` logic) — Module: consumer-service
- Review: Returns `Health.down()` as soon as any handled event's DLQ has >0 messages. Under sustained partial failure, the entire service reports globally DOWN even though most message types and processing capacity are healthy — a single stuck message can trigger fleet-wide remediation that doesn't fix the DLQ backlog.
- Fix: Distinguish "some DLQ activity exists" (info/metric) from an actual capacity/availability signal for orchestration decisions; keep DLQ depth as a monitored metric rather than the sole DOWN trigger.

### [QUAL-011] [Medium] No RabbitMQ HA/clustering — single broker instance is a scaling and availability ceiling
- Location: `docker-compose.yml` (`rabbitmq` service, single instance); `event-contract-kit/.../EventTopologyFactory.java` (classic durable queues, no `x-queue-type: quorum`) — Module: infrastructure, event-contract-kit
- Review: At the 10,000–100,000 concurrent user scale, a single non-clustered broker with classic queues is both a throughput ceiling and a single point of failure — any broker restart drops availability for every producer/consumer until recovery.
- Fix: For any real deployment beyond POC scope, document a RabbitMQ cluster with quorum queues, or a migration path to a managed/clustered broker.

### [QUAL-012] [Medium] Double schema-validation cost per message (producer + consumer) with no per-environment tuning knob exposed
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/serde/JsonSchemaStrategy.java` (`serialize`/`deserialize`); `SchemaMessagingAutoConfiguration` (`validateOnDeserialize=true` hardcoded) — Module: schema-messaging-core
- Review: Every message pays full JSON-Schema validation twice — once producer-side, once consumer-side — with `validateOnDeserialize` not exposed as a `@ConfigurationProperties` toggle. Reasonable defense-in-depth for a POC, but pure duplicated CPU cost per message at high volume with no benchmark to justify it.
- Fix: Expose `events.schema.validate-on-deserialize` as a config binding, and quantify per-message validation cost so the trade-off is evidence-based.

### [QUAL-013] [Medium] Retry ladder has no circuit breaker or jitter — sustained downstream outages amplify redelivery load
- Location: `schema-messaging-core/src/main/java/com/example/messaging/core/consumer/DlxMessageRecoverer.java` (`recover`); `event-contract-kit/.../TopologyNaming.java` (fixed 5s/30s/5m ladder) — Module: schema-messaging-core, event-contract-kit
- Review: Every transient failure funnels through the same fixed TTL ladder with no jitter — messages that failed around the same time redeliver in near-lockstep at each tier boundary, producing periodic bursts against an already-struggling downstream instead of smoothing load.
- Fix: Add jitter to retry TTLs (e.g. `ttl ± random(10%)`), and consider a circuit-breaker/backpressure signal when DLQ/retry depth crosses a threshold.

### [QUAL-014] [Medium] Synchronous, blocking publish path on Tomcat request threads with no async option or thread-pool tuning
- Location: `producer-service/.../OrderController.java`, `CustomerController.java`; `producer-service/pom.xml` (`spring-boot-starter-web`); `producer-service/src/main/resources/application.yml` (no `server.tomcat.threads.*`) — Module: producer-service
- Review: Every REST call blocks its Tomcat worker thread for the full duration of `EventPublisher.publish()`. At high concurrent request volume with a slow broker (see QUAL-011/QUAL-013), producer HTTP threads pool up and the service stops accepting requests before RabbitMQ itself is saturated — a classic thread-pool-exhaustion cascade.
- Fix: Tune `server.tomcat.threads.max`/`accept-count` deliberately with load-tested numbers, and set a bounded send timeout; or move to an async publish model once publisher confirms (QUAL-008) are in place.

---

## 4. Prioritized Fix List

1. [SB-002][High] No publisher confirms/returns — `schema-messaging-core/.../publisher/EventPublisher.java` — enable publisher confirms/returns + mandatory flag, wire callbacks
2. [QUAL-008][High] Same as above, scalability framing — `schema-messaging-core/.../publisher/EventPublisher.java` — same fix as SB-002
3. [SB-003][High] Custom listener factory bypasses Boot's configurer — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — inject `SimpleRabbitListenerContainerFactoryConfigurer`, apply before custom settings
4. [QUAL-007][High] Consumer throughput capped at 1 thread/queue — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — externalize concurrency/prefetch via config
5. [PERF-003][High]* No listener concurrency/prefetch tuning — same file — same fix as SB-003/QUAL-007 (*severity per Performance module; consolidate one fix across all three)
6. [SB-001][High] Unconditional legacy-queue deletion risks message loss on rolling deploy — `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java` — use `ifUnused`/`ifEmpty` delete, or gate as one-time job
7. [ARCH-001][High] Unauthenticated schema-bypass endpoint in production controller — `producer-service/.../OrderController.java` — gate behind `@Profile("demo")` or remove
8. [PERF-001][High] Unconditional per-message INFO logging on hot path — `schema-messaging-core/.../converter/SchemaAwareMessageConverter.java`, `.../publisher/EventPublisher.java` — downgrade to DEBUG or gate on `isDebugEnabled()`
9. [SB-004][Medium] Retry TTLs bound via unvalidated `@Value` — `schema-messaging-core/.../config/RetryTierPropertiesAutoConfiguration.java` — convert to validated `@ConfigurationProperties`
10. [SB-005][Medium] Poison endpoint has no profile gating or auth — `producer-service/.../OrderController.java` — same fix as ARCH-001
11. [JDK-001][Medium] Virtual threads never enabled — both `application.yml` — set `spring.threads.virtual.enabled=true`
12. [PERF-010][Medium] Same as JDK-001 — same fix
13. [ARCH-002][Medium] Services blanket-depend on every domain's contracts — `producer-service/pom.xml`, `consumer-service/pom.xml` — document tradeoff or introduce per-event opt-in
14. [ARCH-003][Medium] Producer exception handling misses publish-path exceptions — `producer-service/.../GlobalExceptionHandler.java` — catch `SchemaMessagingException` base + `MessageConversionException`/`IllegalStateException`
15. [QUAL-004][Medium] Same gap plus broker-connectivity failures — same file — add `AmqpException` handler
16. [ARCH-005][Medium] `event-contract-kit` mixes AMQP naming and schema-identity concerns — `event-contract-kit/.../amqp/topology/` — split into two packages
17. [ARCH-006][Medium] Per-domain autoconfig hand-duplicated — `order-contracts`/`customer-contracts` topology classes — add shared factory in `event-contract-kit`
18. [ARCH-008][Medium] SRP violation: topology configurer also does one-time migration cleanup — `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java` — extract to its own `SmartInitializingSingleton`
19. [QUAL-005][Medium] Same flag has no sunset mechanism — same file — track migration completion, remove flag/method later
20. [ARCH-009][Medium] No extension point (OCP) for a new domain — same as ARCH-006 — same fix
21. [ARCH-013][Medium] No circuit breaker on DLX/retry routing call — `schema-messaging-core/.../consumer/DlxMessageRecoverer.java` — wrap `rabbitTemplate.send` with bounded retry/circuit breaker
22. [PERF-002][Medium] Triplicated per-message logging — producer controllers + listeners — consolidate to one DEBUG-level log per operation
23. [PERF-008][Medium] No GC/heap sizing configured for containers — both `Dockerfile`s, `docker-compose.yml` — add `-XX:MaxRAMPercentage`/`-Xmx` + container memory limits
24. [QUAL-009][Medium] Health probe issues untimeoutted broker RPCs — `consumer-service/.../health/QueueDepthHealthIndicator.java` — add timeout, move to readiness only
25. [QUAL-010][Medium] Health DOWN on any single DLQ message — same file — separate DLQ-activity metric from availability signal
26. [QUAL-011][Medium] No RabbitMQ HA/clustering — `docker-compose.yml`, `event-contract-kit/.../EventTopologyFactory.java` — document quorum-queue/clustered-broker path for production
27. [QUAL-012][Medium] Double schema validation cost with no toggle — `schema-messaging-core/.../serde/JsonSchemaStrategy.java` — expose `validate-on-deserialize` as config
28. [QUAL-013][Medium] Retry ladder has no jitter/circuit breaker — `schema-messaging-core/.../consumer/DlxMessageRecoverer.java`, `event-contract-kit/.../TopologyNaming.java` — add jitter, consider backpressure signal
29. [QUAL-014][Medium] Blocking publish path with no thread-pool tuning — `producer-service/.../OrderController.java`, `CustomerController.java` — tune Tomcat threads or move to async publish
30. [ARCH-004][Low] No REST API versioning — `producer-service/.../OrderController.java`, `CustomerController.java` — adopt `/api/v1/...` prefix
31. [ARCH-007][Low] `RabbitAdmin` bean misplaced in consumer-named class — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — rename class or relocate bean
32. [ARCH-010][Low] `SchemaAwareMessageConverter` concentrates 5 responsibilities — same file — extract `SchemaHeaderReader` if it grows further
33. [ARCH-011][Low] Event-defaulting logic lives in controller, not on the record — `producer-service/.../OrderController.java`, `CustomerController.java` — add static factory methods
34. [ARCH-012][Low] Static utility classes limit customization — `event-contract-kit/.../TopologyNaming.java` — wrap behind `NamingStrategy` interface if a second convention emerges
35. [ARCH-014][Low] Missing Factory/Template Method for domain onboarding — same as ARCH-006 — same fix
36. [SB-006][Low] Field injection inconsistent with rest of codebase — `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` — move to constructor injection
37. [SB-007][Low] Duplicated `NoBeanValidationWebMvcConfiguration` — `producer-service`/`consumer-service` config packages — hoist to shared module or remove from consumer-service
38. [SB-008][Low] Unused `spring-boot-configuration-processor` dependency — `schema-messaging-core/pom.xml` — adopt `@ConfigurationProperties` (SB-004) or remove dependency
39. [JDK-002][Low] Manual `instanceof` chain instead of sealed-type pattern matching — `schema-messaging-core/.../consumer/EventConsumerSupport.java` — optional: seal `PermanentFailure` implementations
40. [JDK-003][Low] Records skip compact-constructor invariant checks — `SchemaCoordinates.java`, `TypeMapping.java`, `RetryTierProperties.java` — add `Objects.requireNonNull`/positivity checks
41. [PERF-004][Low] Schema catalog eager-load has no parallelism — `schema-messaging-core/.../schema/LocalSchemaCatalog.java` — parallelize `warm()` loop if catalog scales up
42. [PERF-005][Low] Connection factory channel cache at defaults — both `application.yml` — set `spring.rabbitmq.cache.channel.size` after load testing
43. [PERF-006][Low] Full stack trace built before truncation — `schema-messaging-core/.../consumer/EventConsumerSupport.java` — cap trace depth while writing
44. [PERF-009][Low] Per-message JsonNode churn (informational) — `schema-messaging-core/.../serde/JsonSchemaStrategy.java` — no action; re-measure if payloads/throughput grow
45. [PERF-011][Low] Health probe issues sequential broker RPCs — `consumer-service/.../health/QueueDepthHealthIndicator.java` — parallelize/cache if handled-type count grows
46. [QUAL-001][Low] Byte-count truncation can split UTF-8 characters — `schema-messaging-core/.../consumer/EventConsumerSupport.java` — decode defensively or truncate on char boundary
47. [QUAL-002][Low] Boilerplate duplication (low-risk framing) — `order-contracts`/`customer-contracts` — optional helper if a third domain is added
48. [QUAL-003][Low] Inconsistent request-DTO mapping pattern — `producer-service/.../OrderController.java`, `CustomerController.java` — standardize on one convention
49. [QUAL-006][Low] Legacy decommission re-runs redundantly every startup — `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java` — same remediation as QUAL-005, or self-disabling marker
