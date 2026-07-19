# Spring Boot Enterprise Production Readiness Review

_Generated: 2026-07-19 12:25 — reviewer framing: Principal Software Architect, JVM Performance Engineer, Spring Boot Expert, Security Architect, Distributed Systems Expert, Production Reliability Engineer._

## 1. Stack Summary

- **Spring Boot version:** 4.1.0 (BOM imported, not `spring-boot-starter-parent`)
- **JDK version:** 25 (`maven.compiler.release=25` + `maven-toolchains-plugin` requiring JDK 25)
- **Language(s):** Java only
- **Build tool:** Maven (committed `./mvnw` wrapper; Maven 3.9.11)
- **Layout:** Multi-module, 7 modules — `schema-messaging-core`, `event-contract-kit`, `schema-gen-tools`, `order-contracts`, `customer-contracts`, `producer-service` (:8081), `consumer-service` (:8082)
- **Run mode:** Parallel subagents (one per selected area)
- **Areas run:** 1 Architecture & Design · 2 Spring Boot & JDK · 3 Performance & Runtime · 7 Ops, Cloud & Build · 8 Quality & Scalability
- **Areas skipped:** 4 Data & API · 5 Security & Resilience · 6 Observability & Testing

## 2. Executive Summary

This is a well-architected schema-governed messaging POC: module boundaries are clean, `core ↛ contracts` / `contracts ↛ core` dependency direction is machine-enforced, the code-first → generated-schema → classpath-validation pipeline is coherent, and hot-path components (Jackson `ObjectMapper`, `JsonSchemaFactory`, compiled `JsonSchema`) are correctly reused and thread-safe with no locks on the message path. The gaps that block production are operational and scale-related, not architectural: single-threaded consumers that ignore Boot's listener config, fire-and-forget publishes with no confirms/backpressure, no Kubernetes manifests or liveness/readiness split, root containers, and no dependency vulnerability scanning.

**Go / No-Go verdict: NO-GO for production** — driven by 5 High findings (no publisher confirms → silent message loss under load, single consumer thread per queue, no K8s manifests/probe split, root containers, no SCA/vulnerability scanning). Fully acceptable as a **POC** at its stated scope.

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 5 |
| Medium | 22 |
| Low | 33 |

## 3. Findings

## 1. Overall Architecture Review

### [ARCH-001] [Medium] Exchange-name convention is unvalidated; DLX/retry can silently collapse onto the main exchange
- Location: `event-contract-kit/.../amqp/topology/TopologyNaming.java` (`dlxExchangeName`/`retryExchangeName`) + `EventMapping.exchange()` — Module: event-contract-kit
- Review: DLX and retry names are derived via `mainExchangeName.replaceFirst("\\.exchange$", ".dlx")`, but `@EventMapping.exchange()` is validated only non-blank. A domain declaring `exchange = "events.foo"` yields `dlx == retry == main`, so `DlxMessageRecoverer` republishes failures onto the main exchange — poison loop, no DLQ isolation. Existing domains work by naming discipline alone.
- Fix: Validate the `.exchange` suffix in `EventMappingValidator.validate` / `IndexedEventMappings.toMapping`, or model exchanges as an explicit triple.

### [ARCH-002] [Medium] Global cross-domain uniqueness of event simple names is a hidden extensibility constraint
- Location: `schema-messaging-core/.../schema/LocalSchemaCatalog.java` (constructor) + `event-contract-kit/.../mapping/IndexedEventMappings.java` — Module: schema-messaging-core / event-contract-kit
- Review: Schema files are located by class simple name, not by coordinates/artifactId. Two events sharing a simple name across domains resolve to the same `*.schema.json`; caught only incidentally by the `MappingBeanNames` collision guard, and `artifactId` overrides don't help because the catalog ignores artifactId for file naming.
- Fix: Key the schema resource path on `SchemaCoordinates` (group + artifactId), consistent with `TypeMappingRegistry.byCoordinates`.

### [ARCH-003] [Low] Failure re-routing ACKs the original before the retry/DLQ copy is confirmed durable
- Location: `schema-messaging-core/.../consumer/DlxRoutingAdvice.java` (`invoke`) + `DlxMessageRecoverer.recover` — Module: schema-messaging-core
- Review: On failure the advice calls `recoverer.recover(...)` (plain `rabbitTemplate.send`) then returns `null` to ACK the original. No publisher confirms/returns; not transactional with the ACK. A dropped republish (unroutable key, broker failover) loses the message despite the at-least-once claim.
- Fix: Enable publisher confirms/returns on the recoverer's `RabbitTemplate`; treat unconfirmed/returned as failure (rethrow → nack).

### [ARCH-004] [Low] Redundant full application-context reflection scans at startup
- Location: `schema-messaging-core/.../config/SchemaMessagingAutoConfiguration.java` (`typeMappingRegistry`), `SchemaMessagingConsumerAutoConfiguration.java` (`rabbitAdmin`), `config/OnBitsEventHandlerPresentCondition.java` (`matches`) — Module: schema-messaging-core
- Review: `BitsEventHandlerScanner.discoverHandledJavaTypes(...)` (full bean-def + per-bean reflection) runs 3+ times at bootstrap even though `HandledEventTypesCache` exists to compute the set once. Startup-only, but undercuts the single-source-of-truth intent.
- Fix: Have registry / `rabbitAdmin` conditionality consume a single memoized scan.

## 2. Multi-Module Structure

### [ARCH-005] [Medium] `event-contract-kit` conflates pure value types, AMQP topology, and Spring bean-registration machinery
- Location: `event-contract-kit/.../amqp/topology/mapping/` (`SchemaCoordinates`, `SchemaType`, `TypeMapping` alongside `EventMappingRegistrar`, `IndexedEventMappings`, `RegisterEventMappings`) — Module: event-contract-kit
- Review: The "domain-agnostic value types" jar also carries a Spring `ImportBeanDefinitionRegistrar` and depends on `spring-rabbit`/spring-context. Consumers needing only `SchemaCoordinates`/`SchemaType` drag in AMQP + bean-definition plumbing. Schema-registry concepts live under the `amqp.topology.mapping` package.
- Fix: Split into a framework-free `schema-contract-kit` (value types) and `amqp-topology-kit` (Spring/AMQP wiring); at minimum relocate the schema-identity types out of `amqp.topology`.

### [ARCH-006] [Low] Duplicated `SchemaFileNaming` class across two modules
- Location: `schema-messaging-core/.../schema/SchemaFileNaming.java` and `schema-gen-tools/.../SchemaFileNaming.java` (byte-identical) — Module: schema-messaging-core / schema-gen-tools
- Review: Intentional duplication (parity-tested) to keep the two modules mutually dependency-free, but a drift surface: an escaped divergence silently breaks runtime schema resolution.
- Fix: Acceptable for POC; if simplifying, host the single algorithm in the shared `event-contract-kit` leaf.

## 3. Spring Boot Best Practices

### [SB-001] [Medium] Auto-configurations have no `@ConditionalOnClass` back-off guards
- Location: `schema-messaging-core/.../config/SchemaMessagingAutoConfiguration.java` (line 34) and `SchemaMessagingConsumerAutoConfiguration.java` (line 54) — Module: schema-messaging-core
- Review: These `@AutoConfiguration` classes reference AMQP types (`RabbitTemplate`, `RabbitAdmin`, `ConnectionFactory`, `SimpleRabbitListenerContainerFactory`) with no `@ConditionalOnClass`. A service with core but without spring-rabbit gets hard wiring failures instead of graceful back-off.
- Fix: Guard with `@ConditionalOnClass(RabbitTemplate.class)`; add `after = RabbitAutoConfiguration` for explicit ordering.

### [SB-002] [Low] Field injection of `@Value` in `ListenerConfiguration`
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration#serviceName`, line 89) — Module: schema-messaging-core
- Review: `@Value(...) private String serviceName;` is field injection in a `@Configuration`, inconsistent with the constructor/param injection used everywhere else; blocks final fields and harms testability.
- Fix: Inject as a `@Bean` method parameter or via constructor.

### [SB-003] [Low] Scattered `@Value` config instead of `@ConfigurationProperties`
- Location: `schema-messaging-core/.../config/RetryTierPropertiesAutoConfiguration.java` (lines 20-22) and `ServiceQueueTopologyAutoConfiguration.java` (line 39) — Module: schema-messaging-core
- Review: `events.retry.tier{0,1,2}.ms` and `events.topology.*` use `@Value` literals with hard-coded defaults; only `events.mappings` uses `@ConfigurationProperties`. No relaxed binding, metadata, or validation.
- Fix: Introduce `@ConfigurationProperties(prefix = "events.retry")` / `events.topology` records.

### [SB-004] [Low] Destructive `decommission-legacy-queues=true` default deletes queues on every boot
- Location: `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java` (`ServiceQueueTopologyConfigurer#decommissionLegacySharedDomainQueues`, lines 89-98) — Module: schema-messaging-core
- Review: Every consumer boot imperatively deletes derived "legacy" queue names, default-on. A name collision or mid-migration rollback would silently drop a live queue and its messages.
- Fix: Default the flag to `false` post-migration, or gate behind a one-shot migration profile; log deleted names + depth first.

### [SB-005] [Low] `GlobalExceptionHandler` maps only `SchemaValidationException`
- Location: `producer-service/.../controller/GlobalExceptionHandler.java` (line 22) — Module: producer-service
- Review: `EventPublisher.publish` also throws `IllegalStateException` (no `TypeMapping`) and `MessageConversionException`, which fall through to a 500 with a leaked message body; only schema-validation becomes a 400.
- Fix: Add handlers for `MessageConversionException`/`IllegalStateException` → 4xx/5xx with sanitized body; consider a catch-all.

## 4. JDK Best Practices

### [SB-006] [Medium] Virtual threads not enabled on I/O-bound Java 25 / Boot 4.1 services
- Location: `producer-service/src/main/resources/application.yml` and `consumer-service/src/main/resources/application.yml` — Module: producer-service, consumer-service
- Review: Both services are I/O-bound (RabbitMQ + REST) on Java 25 / Boot 4.1, yet `spring.threads.virtual.enabled` is unset — the flagship JDK 21+ throughput/footprint modernization.
- Fix: Set `spring.threads.virtual.enabled: true`; validate AMQP container behavior under virtual threads (watch `synchronized` pinning) before treating as default.

### [SB-007] [Low] `Collectors.toList()` where `Stream.toList()` fits
- Location: `schema-messaging-core/.../serde/JsonSchemaStrategy.java` (`validate`, line 162) — Module: schema-messaging-core
- Review: `.collect(Collectors.toList())` builds a read-only error list; inconsistent with the `.toList()` idiom used elsewhere.
- Fix: `.limit(5).toList()` and drop the `Collectors` import.

### [SB-008] [Low] `TypeMappingSelectionProperties` is a mutable getter/setter POJO
- Location: `schema-messaging-core/.../mapping/TypeMappingSelectionProperties.java` (line 17) — Module: schema-messaging-core
- Review: On Java 25 / Boot 4.1 this could be an immutable record with constructor binding, matching the record-first style used everywhere else.
- Fix: Convert to a record with `List.copyOf` defensive defaults.

### [SB-009] [Low] Optional: seal the exception hierarchy / marker
- Location: `schema-messaging-core/.../exception/SchemaMessagingException.java` (line 7) and `PermanentFailure.java` (line 8) — Module: schema-messaging-core
- Review: JDK usage is already strong (records, pattern-matching `switch`, `instanceof` patterns, `Map.copyOf`). Minor lever: `PermanentFailure`/`SchemaMessagingException` are open; sealing documents the closed set. Low payoff — `classify` walks the marker interface by design.
- Fix: `sealed interface PermanentFailure permits ...` if compiler-enforced exhaustiveness is wanted; otherwise leave as-is.

## 5. SOLID Principles

### [ARCH-007] [Medium] `RetryTierProperties` hardcodes exactly 3 tiers, contradicting the N-tier-generic retry ladder (OCP)
- Location: `schema-messaging-core/.../config/RetryTierProperties.java` (record `tier0Ms,tier1Ms,tier2Ms` + `toArray`) and `RetryTierPropertiesAutoConfiguration.java` — Module: schema-messaging-core
- Review: `EventTopologyFactory`, `TopologyNaming.tierSuffix`, and `DlxMessageRecoverer` are all written for N tiers, but the single source of truth is a fixed 3-field record bound to `events.retry.tier{0,1,2}.ms`. Adding/removing a tier requires editing the record + binding, defeating the generic ladder.
- Fix: Bind tiers as a `List<Duration>`/`long[]` via `@ConfigurationProperties(events.retry.tiers)`.

### [ARCH-008] [Low] `EventPublisher` depends on the concrete converter rather than the `MessageConverter` abstraction (DIP)
- Location: `schema-messaging-core/.../publisher/EventPublisher.java` (constructor param `SchemaAwareMessageConverter`) — Module: schema-messaging-core
- Review: `EventPublisher` uses only `toMessage(...)`, a `MessageConverter` method, yet pins the concrete type — needless coupling that blocks decorating/substituting the converter.
- Fix: Depend on `org.springframework.amqp.support.converter.MessageConverter`.

### [ARCH-009] [Low] `SchemaAwareMessageConverter` constructor performs eager cross-registry validation (SRP)
- Location: `schema-messaging-core/.../converter/SchemaAwareMessageConverter.java` (constructor, lines 55-66) — Module: schema-messaging-core
- Review: The constructor iterates the whole `TypeMappingRegistry` and calls `strategy.warm(...)`, doubling as an application-wide fail-fast warmer beyond per-message conversion.
- Fix: Extract the warm-all pass into a dedicated `SmartInitializingSingleton` startup validator bean.

## 6. Object-Oriented Design

### [ARCH-010] [Medium] `ResolvedSchema` leaks its internal `byte[]` via the record accessor
- Location: `schema-messaging-core/.../model/ResolvedSchema.java` (record component `byte[] rawContent`) — Module: schema-messaging-core
- Review: `LocalSchemaCatalog` defensive-copies on construction (`bytes.clone()`), but the record's auto-generated `rawContent()` returns the reference directly. A single mutation corrupts the shared cached schema for all subsequent messages — the upfront clone is undone by the leaky accessor.
- Fix: Make `ResolvedSchema` a class that clones on read, or store the schema as an immutable `String`/parsed node.

### [ARCH-011] [Low] `TypeMapping` fuses schema-identity and AMQP-routing concerns
- Location: `event-contract-kit/.../amqp/topology/mapping/TypeMapping.java` (record with `coordinates`, `schemaType`, `routingKey`, `exchange`) — Module: event-contract-kit
- Review: The validation/catalog path never needs routing; the publisher/topology path never needs schema content. Low cohesion in the most widely-shared type; contributes to ARCH-005.
- Fix: Optional — compose `TypeMapping` from a `SchemaBinding` and a `RouteBinding`.

## 7. Design Patterns

Pattern usage is largely correct: Strategy (`SerializationStrategy`/`JsonSchemaStrategy`), Factory (`DomainTopology.of`, `EventTopologyFactory`, `Mappings`), Adapter (`SchemaAwareMessageConverter`), Registrar (`EventMappingRegistrar`), Interceptor/Chain (`DlxRoutingAdvice`), and a clean Marker Interface (`PermanentFailure`) enabling self-classification in `EventConsumerSupport.classify`. One minor issue:

### [ARCH-012] [Low] `DlxMessageRecoverer` computes a retry TTL it never uses for routing
- Location: `schema-messaging-core/.../consumer/DlxMessageRecoverer.java` (`recover`, `long ttlMs = retryDelaysMs[retryCount];`) — Module: schema-messaging-core
- Review: `ttlMs` is read from the tier array but only logged; the actual delay is enforced by the retry queue's declared `.ttl(...)`. The value is effectively dead and can mislead a maintainer.
- Fix: Drop the local `ttlMs` (log the tier index), or comment that TTL is enforced by the queue declaration.

## 8. Performance Review

### [PERF-001] [Medium] Two INFO logs on every publish hot path
- Location: `schema-messaging-core/.../publisher/EventPublisher.java` (`publish`, line 51) + `converter/SchemaAwareMessageConverter.java` (`toMessage`, line 95) — Module: schema-messaging-core
- Review: Every publish emits two synchronous `log.info(...)` calls with argument formatting on the critical path; INFO is enabled by default, so this is per-message logging I/O at high rates.
- Fix: Demote both to DEBUG (diagnostic, not operational).

### [PERF-002] [Low] Redundant `TypeMapping` lookup per publish
- Location: `schema-messaging-core/.../publisher/EventPublisher.java` (`publish`, line 44) — Module: schema-messaging-core
- Review: `publish()` calls `findByJavaType(...)`, then `toMessage()` repeats the same lookup for the same object — two `Optional` allocations + two map lookups per message (each O(1), minor).
- Fix: Resolve the mapping once and pass exchange/routingKey into the converter, or have `toMessage` return the resolved mapping.

### [PERF-003] [Low] Redundant startup bean/classpath scans
- Location: `schema-messaging-core/.../consumer/BitsEventHandlerScanner.java` (`discoverHandlerBindings`/`discoverHandledJavaTypes`) — Module: schema-messaging-core
- Review: Full bean-def + `MethodIntrospector` reflection invoked independently by `rabbitAdmin`, `typeMappingRegistry`, `HandledEventTypesCache`, and `BitsEventHandlerRegistrar`; only two share the memoized cache. Startup-only, negligible runtime impact.
- Fix: Reuse a single memoized scan across all startup consumers. Optional for POC.

## 9. Memory Analysis

No issues found. Shared converter state is immutable (`Map.copyOf`), `LocalSchemaCatalog` holds seven small bounded schema `byte[]`s for process lifetime, and `JsonSchemaStrategy#compiledSchemaCache` is bounded by the fixed coordinate set. No `ThreadLocal`, no growing static collections, no leaked buffers.

## 10. Garbage Collection Review

### [PERF-004] [Medium] Two-pass tree materialization per message drives object churn
- Location: `schema-messaging-core/.../serde/JsonSchemaStrategy.java` (`serialize` lines 104-106, `deserialize` lines 120-122) — Module: schema-messaging-core
- Review: Produce does `valueToTree → validate → writeValueAsBytes`; consume does `readTree → validate → convertValue`. Each message allocates a full intermediate `JsonNode` tree plus a `Set<ValidationMessage>` — short-lived young-gen garbage that raises allocation rate / minor-GC frequency under load (not a leak; compiled schemas are cached).
- Fix: Largely unavoidable when validating against the tree; acceptable for POC. If throughput matters, profile and consider a streaming `JsonParser` validation path.

### [PERF-005] [Low] No explicit GC selection or heap sizing
- Location: service launch config (no JVM flags in `docker-compose.yml` / `application.yml`) — Module: producer-service / consumer-service
- Review: Services start with JDK-25 defaults (G1, ergonomic heap); fine for POC, but a latency-sensitive messaging service benefits from a deliberate GC choice and fixed heap.
- Fix: For production set `-Xms`=`-Xmx`; consider Generational ZGC if p99 pause latency matters, else G1. Informational.

## 11. Concurrency Review

### [PERF-006] [Medium] Listener container is single-threaded and not tunable via config
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration#rabbitListenerContainerFactory`, lines 106-118) — Module: schema-messaging-core
- Review: The `SimpleRabbitListenerContainerFactory` is hand-built and never sets `concurrentConsumers`/`prefetchCount`, and bypasses `SimpleRabbitListenerContainerFactoryConfigurer`, so `spring.rabbitmq.listener.simple.*` is ignored. One consumer thread per queue — a hard throughput ceiling. (Duplicate of QUAL-007.)
- Fix: Apply the Boot configurer (`configurer.configure(factory, cf)`) then overlay converter + advice; expose concurrency/prefetch via yml.

### [PERF-007] [Medium] `UUID.randomUUID()` per publish contends on shared `SecureRandom`
- Location: `schema-messaging-core/.../converter/SchemaAwareMessageConverter.java` (`ensureCorrelationId`, line 159) — Module: schema-messaging-core
- Review: Each publish without a preset correlation id calls `UUID.randomUUID()`, drawing from a single static `SecureRandom` with `synchronized nextBytes` — a lock-contention hotspot under many concurrent publisher threads.
- Fix: A correlation id needs no CSPRNG strength; generate from `ThreadLocalRandom` (`new UUID(r.nextLong(), r.nextLong())`).

## 22. Docker & Kubernetes

### [OPS-101] [High] No Kubernetes/Helm manifests — orchestration is Docker Compose only
- Location: repo root `docker-compose.yml`; no `k8s/`, `helm/`, or manifests exist — Module: producer-service, consumer-service
- Review: The only deployment artifact is `docker-compose.yml` (single-host, dev-oriented). No Deployments, Services, ConfigMaps/Secrets, HPA, or PDB — nothing expresses probes, limits, replicas, rolling-update strategy, or secret injection for a cluster.
- Fix: Add a `k8s/` (or Helm chart) with a Deployment per service (replicas, RollingUpdate `maxUnavailable/maxSurge`), Service, distinct liveness/readiness probes, `resources.requests/limits`, `terminationGracePeriodSeconds`, and ConfigMap/Secret for broker creds.

### [OPS-102] [High] Containers run as root — no non-root `USER`
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile` (no `USER`) — Module: producer-service, consumer-service
- Review: Both Dockerfiles run `java -jar` as UID 0. A compromised process has root inside the container; hardened clusters (PodSecurity `restricted`) reject root containers.
- Fix: Add `RUN useradd -r -u 10001 app` and `USER 10001:10001`.

### [OPS-103] [Medium] No liveness/readiness distinction; composite health flips DOWN on DLQ backlog
- Location: `consumer-service/.../health/QueueDepthHealthIndicator.java:77`; both `application.yml` (no health groups/`probes.enabled`); `docker-compose.yml:107` — Module: consumer-service
- Review: `QueueDepthHealthIndicator` returns DOWN whenever a DLQ holds messages (a data condition, not a compute failure), and with no health groups that DOWN lands in the single composite `/actuator/health`. Wired to a k8s liveness probe, a DLQ backlog would restart the consumer that must drain it; on readiness it pulls the pod from rotation. (Related: QUAL-011.)
- Fix: Enable probe groups; keep DLQ depth out of liveness/readiness; reserve DOWN for broker-unreachable and report DLQ depth as a metric.

### [OPS-104] [Medium] No resource requests/limits or JVM container memory sizing
- Location: `docker-compose.yml` (no `deploy.resources`/`mem_limit`); Dockerfiles `ENTRYPOINT ["java","-jar","app.jar"]` — Module: producer-service, consumer-service
- Review: No memory/CPU bounds anywhere; JVM defaults to `MaxRAMPercentage=25`, wasting most of a small container and risking OOM surprises under a future limit.
- Fix: Set limits in the deployment manifest and size the heap (`-XX:MaxRAMPercentage=75`) with matching `resources.limits.memory`.

### [OPS-105] [Low] Single-stage image not layer-optimized (fat jar copied whole, curl via apt)
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile` (lines 2-5) — Module: producer-service, consumer-service
- Review: The whole fat jar is one layer, so any code change re-ships all dependencies; `apt-get install curl` bloats the JRE image purely for the compose healthcheck.
- Fix: Use Spring Boot layered jars (`-Djarmode=tools ... extract --layers`) and COPY each layer; drop apt `curl`.

### [OPS-106] [Low] No graceful shutdown drain window at container level
- Location: `docker-compose.yml` (no `stop_grace_period`); no `terminationGracePeriodSeconds` — Module: producer-service, consumer-service
- Review: The exec-form ENTRYPOINT delivers SIGTERM to PID 1 java, but nothing coordinates a drain window with the app shutdown timeout (OPS-303), so in-flight deliveries/requests can be cut.
- Fix: `stop_grace_period: 30s` in compose (and `terminationGracePeriodSeconds` in k8s) aligned with the graceful-shutdown timeout.

## 23. Cloud Readiness

### [OPS-201] [Medium] Only broker connection is externalized; no cloud-native config source
- Location: `producer-service/.../application.yml:4-8`, `consumer-service/.../application.yml:4-8` — Module: producer-service, consumer-service
- Review: RabbitMQ host/port/user/pass are env-overridable (good), but `server.port`, actuator exposure, and `events.demo.poison-endpoint` are hardcoded, with no Config/ConfigMap/secrets-manager integration; credentials default to `guest/guest`.
- Fix: Externalize all env-varying values; source secrets from a Secret/secrets manager; document the env-var contract.

### [OPS-202] [Low] Statelessness/horizontal scaling holds but is undocumented for scale-out
- Location: `docker-compose.yml:77-111` (no replica/scaling config); README topology section — Module: producer-service, consumer-service
- Review: Services are effectively stateless and per-service queues give correct competing-consumer semantics at N replicas, but nothing declares/verifies multi-replica behavior.
- Fix: Add a scaling note/test confirming multiple `consumer-service` replicas share one queue as competing consumers; keep idempotency as documented downstream scope.

## 24. Production Readiness

### [OPS-301] [Medium] No metrics/monitoring pipeline (Micrometer/Prometheus removed)
- Location: `CLAUDE.md` (metrics/tracing removed); both `application.yml` expose only `health,info`; no `micrometer-registry-*` — Module: producer-service, consumer-service
- Review: No way to observe queue depth, consumer lag, DLQ growth, JVM/GC, or publish rate in production; the DLQ signal exists only as a health flag, not an alertable metric.
- Fix: Re-add `micrometer-registry-prometheus`, expose `prometheus` on the management port, alert on DLQ depth and consumer lag as metrics (decoupled from probes per OPS-103).

### [OPS-303] [Medium] No graceful shutdown / lifecycle timeout in app config
- Location: both `application.yml` (no `server.shutdown`, no `spring.lifecycle.timeout-per-shutdown-phase`) — Module: producer-service, consumer-service
- Review: Boot defaults to immediate shutdown. On SIGTERM the producer can drop in-flight HTTP and the consumer can interrupt in-flight handling rather than draining, causing redelivery churn under at-least-once.
- Fix: `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 25s`.

### [OPS-302] [Low] No backup/DR posture stated for the standing registry Postgres
- Location: `docker-compose.yml:4-21` (`pgdata` named volume, no backup) — Module: (infra)
- Review: Apicurio Postgres uses a local named volume with no backup/replication. CI/governance-only (runtime never calls the registry), so blast radius is limited to schema history, but a lost volume loses the compat-check baseline.
- Fix: Document it as CI-only/non-critical; for the standing host add a scheduled `pg_dump` or managed Postgres.

## 25. Code Quality

### [QUAL-001] [Low] Long method with repetitive null-guard DTO→record mapping
- Location: `producer-service/.../controller/OrderController.java` (`fulfillOrder`) — Module: producer-service
- Review: Three near-identical ternary null-guard blocks (`buyer`, `shipping`, `payment`) inline in one handler; boilerplate obscures the intent (let schema validation own the 400).
- Fix: Extract per-nested `static` mappers, e.g. `OrderBuyer map(BuyerRequest r){ return r == null ? null : new OrderBuyer(...); }`.

### [QUAL-002] [Low] Listener dereferences nested fields without null-guard; relies on validation staying on
- Location: `consumer-service/.../listener/OrderEventListener.java` (`onOrderFulfilled`) — Module: consumer-service
- Review: `event.buyer().displayName()` etc. are dereferenced unguarded, unlike `CustomerEventListener#onCustomerAddressAdded`. Safe only because the schema marks those objects `required` with `validateOnDeserialize=true`; disabling consumer-side validation would NPE → misclassify as transient → DLQ.
- Fix: Null-guard the log dereferences (match `CustomerEventListener`) or document the dependency.

### [QUAL-003] [Low] Magic numbers in failure-header and validation-error truncation
- Location: `schema-messaging-core/.../consumer/EventConsumerSupport.java` (truncate `512`) and `serde/JsonSchemaStrategy.java` (`.limit(5)`) — Module: schema-messaging-core
- Review: The 4KB stack-trace cap is a named constant, but the 512-byte message cap and 5-error cap are inline literals.
- Fix: Promote to `MAX_FAILURE_MESSAGE_BYTES = 512` and `MAX_REPORTED_VALIDATION_ERRORS = 5`.

## 26. Dependency Review

### [OPS-401] [High] No dependency vulnerability scanning (no OWASP/Dependabot/SCA)
- Location: `.github/workflows/*` (5 workflows, none scan deps); no `dependabot.yml`, no `dependency-check` plugin — Module: (build/CI)
- Review: No `org.owasp:dependency-check-maven`, no Dependabot/Renovate, no Snyk/Trivy. Transitive CVEs in the Spring Boot 4.1 / networknt / victools / Jackson graph go undetected.
- Fix: Add Dependabot/Renovate for maven + github-actions + docker, and a CI SCA gate (OWASP dependency-check or Trivy on built images).

### [OPS-402] [Medium] Aging base/broker images pinned to floating or older tags
- Location: `docker-compose.yml` (`rabbitmq:3.13-management`, `apicurio-registry:3.2.0`, `postgres:17`); Dockerfiles `FROM eclipse-temurin:25-jre` — Module: (infra)
- Review: RabbitMQ 3.13 is maturing (4.x GA); `eclipse-temurin:25-jre` is a floating major tag (content drifts between builds).
- Fix: Track RabbitMQ 4.x (validate the AMQP client) and pin base images by digest.

### [OPS-403] [Low] Two Jackson generations coexist (Jackson 3 starter + Jackson 2 jsr310)
- Location: `schema-messaging-core/pom.xml:41-53` (jackson-databind + jackson-datatype-jsr310) — Module: schema-messaging-core
- Review: Jackson 2 (`com.fasterxml.jackson`) and Jackson 3 (`tools.jackson`) are intentionally both present for `Instant` support; version-managed by the BOM, but a dual-Jackson classpath is a foot-gun.
- Fix: Keep; add a round-trip test asserting `Instant` serializes as ISO-8601 through the converter; revisit once Jackson 3 jsr310 is on the BOM.

## 27. Build Review

### [OPS-501] [Medium] Images are not self-contained / reproducible — depend on a host-built jar
- Location: `producer-service/Dockerfile:5` / `consumer-service/Dockerfile:5` (`COPY target/*.jar`); `docker-compose.yml:73-76` — Module: producer-service, consumer-service
- Review: `docker compose up --build` copies a pre-existing `target/*.jar`; the container performs no build. A stale/missing host `target/` silently ships old bytes or fails; the image is not reproducible from source alone.
- Fix: Add a multi-stage Dockerfile (maven/temurin build stage → copy layered jar into runtime stage); keep the host-jar path as a dev-only override.

### [OPS-502] [Low] `build-and-test.yml` never runs integration tests except manual dispatch
- Location: `.github/workflows/build-and-test.yml:59-61` (`if: github.event_name == 'workflow_dispatch' && inputs.run_verify`) — Module: (CI)
- Review: PRs/pushes run only Surefire unit tests; the Testcontainers `*IT.java` suite (real RabbitMQ, DLX/retry) runs only on human dispatch. Integration regressions can merge unnoticed.
- Fix: Run `./mvnw verify` on a schedule or on PRs touching `schema-messaging-core`/`*-service`.

### [OPS-503] [Low] Maven Wrapper distribution not integrity-verified in CI
- Location: `.github/workflows/*` invoke `./mvnw` with no checksum; `.mvn/wrapper/maven-wrapper.properties` — Module: (build/CI)
- Review: CI runs the committed `./mvnw`, which downloads Maven 3.9.11 without a verified `distributionSha256Sum` — a supply-chain gap.
- Fix: Add `distributionSha256Sum` (and `wrapperSha256Sum`) to `maven-wrapper.properties`.

### [OPS-504] [Low] `toolchains.xml` is an unpinned external prerequisite duplicated across workflows
- Location: `pom.xml:176-193` (toolchains requires JDK 25); CI writes `~/.m2/toolchains.xml` inline in every workflow — Module: (build)
- Review: Every dev and CI job must hand-provide a JDK-25 `~/.m2/toolchains.xml`; the same heredoc is duplicated across `build-and-test.yml` and `schema-drift-check.yml` — a maintenance smell and common first-run failure.
- Fix: Factor the toolchains-write into a shared composite action/reusable step; keep the README snippet for local devs.

_Build hygiene is otherwise strong: single-source-of-versions parent POM with the Spring Boot BOM imported and the plugin version tied to `${spring-boot.version}`; all plugins version-pinned in `pluginManagement`; `maven-enforcer` machine-bans on `core ↛ contracts` and `contracts ↛ core`; data-driven discovery-based schema drift/compat CI._

## 28. Maintainability

### [QUAL-004] [Medium] Destructive legacy-queue decommission is default-on with no removal tracking
- Location: `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java` (`ServiceQueueTopologyConfigurer#decommissionLegacySharedDomainQueues`) — Module: schema-messaging-core
- Review: `events.topology.decommission-legacy-queues` defaults to `true`, so every consumer boot issues `rabbitAdmin.deleteQueue(...)` for legacy names indefinitely — a permanent migration mechanism with no tracked end date that can silently drop a queue on a name collision. (Related: SB-004.)
- Fix: Track migration completion (ADR/issue with a target release), then delete the flag, `decommissionLegacySharedDomainQueues`, and `TopologyNaming.legacySharedDomainQueueNames` in one cleanup PR.

### [QUAL-005] [Low] Retry ladder structurally fixed at 3 tiers; contradicts "add a tier" documentation
- Location: `schema-messaging-core/.../config/RetryTierProperties.java` (record `RetryTierProperties(tier0Ms, tier1Ms, tier2Ms)`) — Module: schema-messaging-core
- Review: Javadoc says "changing or adding a tier here applies," but the type is a 3-field record; a fourth tier requires editing the record, `toArray()`, and the bindings. (Same root cause as ARCH-007.)
- Fix: Model tiers as a `List<Long>`/`long[]` bound from `events.retry.tiers[*].ms`, or correct the doc to state the arity is fixed.

### [QUAL-006] [Low] Demo poison endpoint ships in the production controller package
- Location: `producer-service/.../controller/PoisonOrderController.java` — Module: producer-service
- Review: The validation-bypass poison endpoint is in the main REST surface, gated only by `events.demo.poison-endpoint` (`true` in base `application.yml`, off only under `prod`). A missing `prod` profile leaves it exposed.
- Fix: Move to a test/dev-only source set, or default the property to `false` and opt-in for local.

## 29. Scalability Assessment

### [QUAL-007] [High] Single consumer thread per queue — custom listener factory bypasses Boot's configurer
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java` (`ListenerConfiguration#rabbitListenerContainerFactory`) — Module: schema-messaging-core
- Review: Built via `new SimpleRabbitListenerContainerFactory()` without the `SimpleRabbitListenerContainerFactoryConfigurer`, so `spring.rabbitmq.listener.simple.concurrency|max-concurrency|prefetch` are silently ignored (and unset in yml). Each of the 7 queues is drained by one thread — the primary bottleneck at 1k–10k+; the only scaling lever is more pods. (Duplicate of PERF-006.)
- Fix: `configurer.configure(factory, cf)` first, then overlay converter + advice; expose concurrency/prefetch via yml and load-test.

### [QUAL-008] [High] Fire-and-forget publish — no confirms/returns/mandatory, so no backpressure and silent loss under load
- Location: `schema-messaging-core/.../publisher/EventPublisher.java` (`publish`) — Module: schema-messaging-core
- Review: `rabbitTemplate.send(...)` with no `publisher-confirm-type`, `publisher-returns`, or `mandatory` anywhere; controllers return 201 as soon as `send()` returns locally. A broker nack, connection drop, or unroutable key is dropped invisibly — the documented at-least-once guarantee is unbacked.
- Fix: `spring.rabbitmq.publisher-confirm-type=correlated`, `publisher-returns=true`, `RabbitTemplate.setMandatory(true)`, register confirm/return callbacks so a nack/return fails the publish (5xx / outbox).

### [QUAL-009] [Medium] Every message is JSON-parsed and schema-validated twice across the path
- Location: `schema-messaging-core/.../serde/JsonSchemaStrategy.java` (`serialize`/`deserialize`) — Module: schema-messaging-core
- Review: Produce validates the tree; consume validates again (`validateOnDeserialize=true` default). Each event pays full networknt validation + a tree build on both sides — doubling per-message CPU at millions/day. (Related: PERF-004.)
- Fix: Make consumer-side validation a toggle (`events.validate-on-deserialize`, default on) so trusted internal flows can drop the second validation; keep it on at trust boundaries.

### [QUAL-010] [Medium] Synchronous blocking publish on Tomcat request threads; virtual threads not enabled
- Location: `producer-service/.../controller/OrderController.java` / `CustomerController.java`; `application.yml` (no `spring.threads.virtual.enabled`) — Module: producer-service
- Review: Each POST validates + serializes + AMQP-sends inline on a blocking servlet thread. At 10k+ concurrent, throughput is capped by the default Tomcat pool (200) × publish latency, with no virtual-thread relief. (Related: SB-006.)
- Fix: `spring.threads.virtual.enabled=true` (after fixing confirms so blocking is bounded); load-test for channel/connection pinning.

### [QUAL-011] [Medium] Shared-DLQ health indicator flips every consumer replica DOWN on a single DLQ message
- Location: `consumer-service/.../health/QueueDepthHealthIndicator.java` (`health`) — Module: consumer-service
- Review: Status is DOWN whenever any per-service DLQ has depth > 0; DLQs are shared across replicas, so one poisoned message makes every replica report DOWN at once. Wired to a k8s readiness probe, that recycles the entire consumer fleet. (Related: OPS-103.)
- Fix: Report DLQ depth as an info metric/alert, not a health DOWN; gate liveness on broker connectivity only.

### [QUAL-012] [Low] Default channel cache (25) undersized vs Tomcat thread pool (200) for publish bursts
- Location: `producer-service/src/main/resources/application.yml` (no `spring.rabbitmq.cache.channel.size`) — Module: producer-service
- Review: With ~200 concurrent request threads each publishing, the default `CachingConnectionFactory` channel cache of 25 forces channel checkout contention/churn under burst.
- Fix: Set `spring.rabbitmq.cache.channel.size` based on measured concurrent-publish load.

### [QUAL-013] [Low] Health probe issues 2×N broker management calls per hit
- Location: `consumer-service/.../health/QueueDepthHealthIndicator.java` (`health` → `queueDepth`) — Module: consumer-service
- Review: Each `/actuator/health` calls `rabbitAdmin.getQueueInfo` twice per mapping (main + DLQ) — 14 broker round-trips per probe with 7 mappings; frequent probing × replicas adds avoidable load to RabbitMQ's management path.
- Fix: Batch/cache queue-info with a short TTL, reduce probe frequency, or use a passive check.

### [QUAL-014] [Low] Positive: services are stateless and horizontally scalable
- Location: `consumer-service/.../listener/*Listener.java`, `producer-service/.../controller/*Controller.java` — Module: producer-service / consumer-service
- Review: No app-level datastore or in-memory session state (compose Postgres is Apicurio storage only); handlers are log-only; shared caches are immutable-after-startup or `ConcurrentHashMap`. The ceiling is per-queue consumer concurrency (QUAL-007), not statefulness.
- Fix: None required; positive confirmation. Add replicas per hot event type once QUAL-007 is addressed.

_Scaling summary: ~100 concurrent — fine as-is. 1,000 — single consumer per queue (QUAL-007) and no publish backpressure (QUAL-008) begin to bite on hot event types. 10,000–100,000 / millions/day — requires consumer concurrency+prefetch (QUAL-007), publisher confirms/outbox (QUAL-008), optional consume-validation toggle (QUAL-009), virtual threads + channel-cache sizing (QUAL-010/012), replicas, and fixing the fleet-wide DLQ health flip (QUAL-011)._

## 4. Prioritized Fix List

1. [OPS-101][High] No Kubernetes/Helm manifests — repo root — add a Deployment/Service/probe/limits chart per service.
2. [OPS-102][High] Root containers — `*/Dockerfile` — add a non-root `USER`.
3. [OPS-401][High] No dependency vulnerability scanning — `.github/workflows` — add Dependabot/Renovate + OWASP/Trivy SCA gate.
4. [QUAL-007][High] Single consumer thread per queue — `SchemaMessagingConsumerAutoConfiguration.java` — apply Boot's listener configurer + yml concurrency/prefetch. _(= PERF-006)_
5. [QUAL-008][High] Fire-and-forget publish, no confirms — `EventPublisher.java` — enable publisher confirms/returns/mandatory + callbacks.
6. [ARCH-001][Medium] Unvalidated exchange-name convention — `TopologyNaming.java` — validate `.exchange` suffix or model exchanges explicitly.
7. [ARCH-002][Medium] Cross-domain simple-name collision — `LocalSchemaCatalog.java` — key schema resolution on `SchemaCoordinates`.
8. [ARCH-005][Medium] `event-contract-kit` conflates value types + Spring wiring — split modules / relocate schema-identity types.
9. [ARCH-007][Medium] Retry ladder hardcodes 3 tiers — `RetryTierProperties.java` — bind tiers as a list. _(= QUAL-005)_
10. [ARCH-010][Medium] `ResolvedSchema` leaks internal `byte[]` — clone on read or store immutable.
11. [SB-001][Medium] Auto-configs lack `@ConditionalOnClass` — add AMQP class guards + ordering.
12. [SB-006][Medium] Virtual threads unset — `application.yml` — `spring.threads.virtual.enabled=true`. _(= QUAL-010)_
13. [PERF-001][Medium] Two INFO logs per publish — demote to DEBUG.
14. [PERF-004][Medium] Two-pass tree churn per message — profile; consider streaming validation. _(= QUAL-009)_
15. [PERF-007][Medium] `SecureRandom` contention on per-publish UUID — use `ThreadLocalRandom`.
16. [OPS-103][Medium] No liveness/readiness split; DLQ backlog flips health — add probe groups. _(= QUAL-011)_
17. [OPS-104][Medium] No resource limits / heap sizing — set limits + `MaxRAMPercentage`.
18. [OPS-201][Medium] Config not fully externalized; `guest/guest` — externalize + secrets manager.
19. [OPS-301][Medium] No metrics pipeline — re-add Micrometer/Prometheus + DLQ/lag alerts.
20. [OPS-303][Medium] No graceful shutdown — `server.shutdown: graceful` + lifecycle timeout.
21. [OPS-402][Medium] Aging/floating base images — track RabbitMQ 4.x, digest-pin bases.
22. [OPS-501][Medium] Images not reproducible (host jar) — multi-stage build from source.
23. [QUAL-004][Medium] Destructive legacy-queue delete default-on — track completion, then remove. _(= SB-004)_
24. [ARCH-003][Low] Recover ACKs before republish confirmed — add confirms to recoverer template.
25. [ARCH-004][Low] Redundant startup scans — reuse one memoized scan. _(= PERF-003)_
26. [ARCH-006][Low] Duplicated `SchemaFileNaming` — host once in `event-contract-kit` (optional).
27. [ARCH-008][Low] `EventPublisher` pins concrete converter — depend on `MessageConverter`.
28. [ARCH-009][Low] Converter constructor warms whole registry — extract startup validator.
29. [ARCH-011][Low] `TypeMapping` fuses identity + routing — compose from two bindings (optional).
30. [ARCH-012][Low] Dead `ttlMs` in recoverer — drop or comment.
31. [SB-002][Low] Field `@Value` injection — use param/constructor injection.
32. [SB-003][Low] Scattered `@Value` config — use `@ConfigurationProperties`.
33. [SB-005][Low] Narrow exception handler — map `MessageConversionException`/`IllegalStateException`.
34. [SB-007][Low] `Collectors.toList()` — use `Stream.toList()`.
35. [SB-008][Low] Mutable properties POJO — convert to a record.
36. [SB-009][Low] Open exception marker — optionally seal `PermanentFailure`.
37. [PERF-002][Low] Redundant per-publish mapping lookup — resolve once.
38. [PERF-005][Low] No explicit GC/heap sizing — set `-Xms=-Xmx`, consider ZGC.
39. [OPS-105][Low] Non-layered image + apt curl — layered jar, drop curl.
40. [OPS-106][Low] No container drain window — `stop_grace_period`/`terminationGracePeriodSeconds`.
41. [OPS-202][Low] Scale-out undocumented — add competing-consumer note/test.
42. [OPS-302][Low] No DR for registry Postgres — document CI-only; scheduled `pg_dump`.
43. [OPS-403][Low] Dual Jackson generations — add `Instant` round-trip test.
44. [OPS-502][Low] IT suite runs only on manual dispatch — run `verify` on schedule/PR.
45. [OPS-503][Low] Wrapper distribution unverified — add `distributionSha256Sum`.
46. [OPS-504][Low] Duplicated toolchains bootstrap — factor into a composite action.
47. [QUAL-001][Low] Long null-guard mapping method — extract per-nested mappers.
48. [QUAL-002][Low] Unguarded listener dereferences — null-guard or document.
49. [QUAL-003][Low] Magic numbers (512, 5) — promote to named constants.
50. [QUAL-006][Low] Poison endpoint in prod controller — dev-only source set / default off.
51. [QUAL-012][Low] Undersized channel cache — set `cache.channel.size`.
52. [QUAL-013][Low] 2×N broker calls per health probe — batch/cache queue-info.
