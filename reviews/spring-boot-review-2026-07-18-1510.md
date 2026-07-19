# Spring Boot Production Readiness Review

## 1. Stack Summary

* **Spring Boot version:** 4.1.0 (BOM)
* **JDK version:** 25 (`maven.compiler.release`)
* **Language(s):** Java
* **Build tool:** Maven (wrapper: Maven 3.9.11)
* **Layout:** multi-module (7) — `schema-messaging-core`, `event-contract-kit`, `order-contracts`, `customer-contracts`, `producer-service`, `consumer-service`, `schema-gen-tools`
* **Run mode:** parallel subagents
* **Areas run:** 1 Architecture & Design · 2 Spring Boot & JDK · 3 Performance & Runtime · 4 Data & API · 7 Ops, Cloud & Build · 8 Quality & Scalability
* **Areas skipped:** 5 Security & Resilience · 6 Observability & Testing

---

## 2. Executive Summary

Schema-governed messaging POC with strong module boundaries (enforcer-banned `core ↛ contracts`, publisher-owned exchanges, per-service queues, classpath `LocalSchemaCatalog`). Architecture fit for a demo is high; production readiness is not. Delivery honesty (no publisher confirms), destructive legacy-queue delete default-on, demo poison path default-on off-`prod`, custom listener factory ignoring Boot concurrency/prefetch, and compose/guest credentials are the main blockers.

**Verdict: No-Go** for production — driven by multiple open **High** findings (delivery, migration safety, deploy defaults, throughput knobs, infra secrets). Acceptable as a local POC with explicit profile/ops gates.

| Severity | Count |
|----------|------:|
| Critical | 0 |
| High | 12 |
| Medium | 28 |
| Low | 22 |

*(Counts after cross-area deduplication. Skipped areas 5–6 may add Security/Observability findings later.)*

---

## 3. Findings

## 1. Overall Architecture Review

### [ARCH-001] [High] Legacy-queue delete runs every boot, can drop messages
- Location: `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java` (`ServiceQueueTopologyConfigurer#decommissionLegacySharedDomainQueues`) — Module: schema-messaging-core
- Review: `rabbitAdmin.deleteQueue(queueName)` with default `events.topology.decommission-legacy-queues=true`. No if-empty/if-unused. Rolling deploy: new instance can delete legacy queues still used by old instances → silent loss. Same concern as SB-003 / QUAL-008 / ARCH-014.
- Fix: Default flag `false` after migration; delete only if empty; or one-shot job, not every consumer boot. Split into a separate decommissioner bean (SRP).

### [ARCH-002] [High] Documented at-least-once publish has no broker confirm/return surface
- Location: `schema-messaging-core/.../publisher/EventPublisher.java` (`EventPublisher#publish`) — Module: schema-messaging-core
- Review: `rabbitTemplate.send(...)` only. No `publisher-confirm-type`, returns, or `mandatory` in repo. Controllers return 201 after local send. Unroutable/dropped publishes invisible. Same as SB-002 / QUAL-001.
- Fix: Correlated confirms + returns + `mandatory=true`; fail publish (and HTTP) on nack/return.

### [ARCH-003] [High] Poison path bypasses validation; on by default off-prod
- Location: `producer-service/.../controller/PoisonOrderController.java`; `producer-service/.../application.yml` (`events.demo.poison-endpoint: true`) — Module: producer-service
- Review: Raw `RabbitTemplate.send` with forged headers + garbage body. Property gate exists, but default yaml is `true`; only `prod` forces `false`. Compose does not set `SPRING_PROFILES_ACTIVE=prod` (OPS-003). Same as SB-006 / API-006 / QUAL-015.
- Fix: Default property `false`; enable only under explicit demo profile; set `prod` (or flag off) in compose/prod images.

### [ARCH-004] [Medium] Single producer owns both domain exchange sets
- Location: `producer-service/.../ProducerApplication.java` (`@Import({OrderPublisherTopology, CustomerPublisherTopology})`) — Module: producer-service
- Review: One process is sole publisher of orders + customers. Failure/deploy of this service takes both domains’ exchange declare + publish surface.
- Fix: Split publisher apps per domain when ownership demands; keep opt-in `@Import` model.

### [ARCH-005] [Medium] Global simple-name identity for schemas + beans
- Location: `LocalSchemaCatalog` constructor; `MappingBeanNames#forType`; `SchemaFileNaming` — Modules: schema-messaging-core, event-contract-kit, schema-gen-tools
- Review: Catalog loads `schemas/` + kebab(simpleName). Bean names from simple name. Classpath `getResourceAsStream` is first-wins if filenames collide across jars.
- Fix: Namespace schema paths (e.g. `schemas/{groupId}/...`) or encode FQCN; document uniqueness as hard rule.

### [ARCH-006] [Medium] `@EventMapping.artifactId` decoupled from schema filename
- Location: `IndexedEventMappings#toMapping`; `LocalSchemaCatalog`; `SchemaGeneratorCli#writeEventSchema` — Modules: event-contract-kit, schema-messaging-core, schema-gen-tools
- Review: Custom `artifactId` becomes coordinates but file always from `getSimpleName()`. Two identity axes.
- Fix: Derive filename from effective artifactId, or ban non-default `artifactId` until paths align.

### [ARCH-007] [Medium] Producer REST error surface incomplete vs publish failures
- Location: `GlobalExceptionHandler.java`; `EventPublisher#publish`; `SchemaAwareMessageConverter#toMessage` — Modules: producer-service, schema-messaging-core
- Review: Handler maps only `SchemaValidationException` → 400. Mapping miss → `IllegalStateException`; other convert/serialize → 500. Same as SB-007 / API-003 / QUAL-005.
- Fix: Map `SchemaMessagingException` hierarchy + conversion/mapping miss to stable 4xx/5xx; prefer ProblemDetail.

### [ARCH-008] [Low] REST produce API unversioned while events are governed
- Location: `OrderController` (`/api/orders`), `CustomerController` (`/api/customers`) — Module: producer-service
- Review: Heavy schema/compat investment; HTTP surface has no `/v1`.
- Fix: Version REST before external clients.

---

## 2. Multi-Module Structure

### [ARCH-009] [Medium] `schema-gen-tools` wired via hard reactor path, not Maven artifact
- Location: `order-contracts/pom.xml` / `customer-contracts/pom.xml` (`exec-maven-plugin` `additionalClasspathElements`) — Modules: order-contracts, customer-contracts
- Review: Build graph depends on filesystem layout (`${maven.multiModuleProjectDirectory}/schema-gen-tools/target/classes`), not a declared plugin artifact dependency.
- Fix: Reactor-aware plugin dep on `schema-gen-tools`, or document mandatory `-am` from root.

### [ARCH-010] [Medium] `event-contract-kit` mixes AMQP topology + schema identity
- Location: `event-contract-kit/.../topology/` vs `.../topology/mapping/` — Module: event-contract-kit
- Review: Kit is leaf + enforcer-clean. Package tree nests registry identity under `amqp.topology`.
- Fix: Top-level packages `...mapping` / `...amqp` without changing dependency edges.

### [ARCH-011] [Medium] Services pull all contract jars; producer warms all mappings
- Location: `producer-service/pom.xml`, `consumer-service/pom.xml`; `TypeMappingSelection.select` — Modules: producer-service, consumer-service, schema-messaging-core
- Review: Both services depend on both `*-contracts`. Pure producer keeps all classpath mappings → full catalog warm.
- Fix: `events.mappings.include` on fat producers; or split deployable by domain.

### [ARCH-012] [Low] Twin `SchemaFileNaming` kept by design
- Location: `schema-messaging-core/.../schema/SchemaFileNaming.java`; `schema-gen-tools/.../SchemaFileNaming.java` — Modules: schema-messaging-core, schema-gen-tools
- Review: Intentional duplicate + parity tests. Drift risk remains. Same as QUAL-011.
- Fix: Keep parity tests in CI; optional shared naming-only jar if drift bites.

### [ARCH-013] [Low] No enforcer ban of `schema-gen-tools` on service runtime
- Location: `producer-service/pom.xml`, `consumer-service/pom.xml` — Modules: producer-service, consumer-service
- Review: Convention only that gen-tools never lands on service CP.
- Fix: Enforcer exclude `schema-gen-tools` on both services.

---

## 3. Spring Boot Best Practices

### [SB-001] [High] Custom listener factory ignores Boot listener properties
- Location: `SchemaMessagingConsumerAutoConfiguration.ListenerConfiguration#rabbitListenerContainerFactory` (L107–117) — Module: schema-messaging-core
- Review: `new SimpleRabbitListenerContainerFactory()` without `SimpleRabbitListenerContainerFactoryConfigurer`. `spring.rabbitmq.listener.simple.*` silently ignored → default 1 consumer/queue, prefetch 250. Same as ARCH-024 / PERF-002 / QUAL-002.
- Fix: Inject configurer, `configure(factory, connectionFactory)`, then overlay converter/advice; expose concurrency/prefetch via yml.

### [SB-004] [Medium] Retry TTLs via `@Value`, not validated `@ConfigurationProperties`
- Location: `RetryTierPropertiesAutoConfiguration.java`; `RetryTierProperties.java` — Module: schema-messaging-core
- Review: Three `@Value` bindings; no positive/ascending validation. Negative/zero can break TTL ladder.
- Fix: `@ConfigurationProperties("events.retry")` + validation (see SB-005 for BV provider caveat).

### [SB-005] [Medium] `ValidationAutoConfiguration` excluded — props validation cannot run
- Location: `ProducerApplication.java`; `ConsumerApplication.java`; both `NoBeanValidationWebMvcConfiguration` — Modules: producer-service, consumer-service
- Review: Apps exclude Boot validation auto-config and replace MVC validator with no-op. `@Validated` on `@ConfigurationProperties` will not enforce at startup.
- Fix: Keep WebMVC no-op if needed; restore BV provider for config-properties binding, or validate in compact constructors.

### [SB-008] [Medium] `@BitsEventHandler` discovery scanned repeatedly at startup
- Location: `SchemaMessagingAutoConfiguration`; `SchemaMessagingConsumerAutoConfiguration`; `OnBitsEventHandlerPresentCondition`; `HandledEventTypesCache`; `BitsEventHandlerRegistrar` — Module: schema-messaging-core
- Review: Full reflection scan runs on multiple paths; only cache memoizes one. Same as PERF-006.
- Fix: Single early discovery bean reused by condition, registry, registrar, admin.

### [SB-009] [Low] Field `@Value` injection in listener auto-config
- Location: `SchemaMessagingConsumerAutoConfiguration.ListenerConfiguration` (L89) — Module: schema-messaging-core
- Review: Mutable field injection; rest of core uses constructor/method-param injection. Same as ARCH-017.
- Fix: Constructor-inject `serviceName` or `@Bean` method param.

### [SB-010] [Low] Duplicate `NoBeanValidationWebMvcConfiguration`
- Location: producer-service + consumer-service copies — Modules: both services
- Review: Near-identical NO_OP `WebMvcConfigurer`. Same as QUAL-012.
- Fix: Keep on producer only, or share one helper.

### [SB-011] [Low] `TypeMappingSelectionProperties` mutable; blank tokens silently skipped
- Location: `TypeMappingSelectionProperties.java`; `TypeMappingSelection.matchesAny` — Module: schema-messaging-core
- Review: Classic getters/setters; blank tokens ignored without fail-fast.
- Fix: Immutable/record + defensive copies; fail fast on blank include/exclude tokens.

### [SB-012] [Low] Hot-path `INFO` logging on every serialize/publish
- Location: `SchemaAwareMessageConverter#toMessage`; `EventPublisher#publish` — Module: schema-messaging-core
- Review: Every successful produce logs at INFO. Same as PERF-001 / QUAL-013.
- Fix: DEBUG for per-message; INFO for lifecycle/failures only.

---

## 4. JDK Best Practices

### [JDK-001] [Medium] JDK 25 pinned; virtual threads never enabled
- Location: root `pom.xml` (`maven.compiler.release` 25); both service `application.yml` — Modules: parent, producer-service, consumer-service
- Review: Blocking Tomcat + Simple listener containers; `spring.threads.virtual.enabled` unset. Same as CONC-001. Pairs poorly with SB-001 (concurrency still 1).
- Fix: Enable VT after wiring Boot listener configurer + raising concurrency; load-test for pinning.

### [JDK-002] [Low] Closed exception taxonomy not sealed
- Location: `PermanentFailure.java`; `SchemaMessagingException.java`; `EventConsumerSupport#classify` — Module: schema-messaging-core
- Review: Marker + cause-chain `instanceof` works; compiler cannot enforce closed permanent set.
- Fix (optional): `sealed interface PermanentFailure permits ...`.

### [JDK-003] [Low] Core value records lack compact-constructor guards
- Location: `RetryTierProperties.java`; `TypeMapping.java`; `SchemaCoordinates.java` — Modules: schema-messaging-core, event-contract-kit
- Review: No null/blank/positive checks; bad construction fails late.
- Fix: Compact constructors with `requireNonNull` / blank / `tierMs > 0`.

### [JDK-004] [Low] Listener assumes non-null nested records without null-safe access
- Location: `OrderEventListener#onOrderFulfilled` — Module: consumer-service
- Review: Nested field chains NPE if validation ever disabled or schema drifts.
- Fix: Null-safe logging or pattern matching; keep schema as authority.

### [JDK-005] [Low] Pattern matching used in places; not on exception classify
- Location: Good: topology `switch` / `DlxMessageRecoverer`; Gap: `EventConsumerSupport#classify` — Module: schema-messaging-core
- Review: Modernization opportunity only after sealing (JDK-002).
- Fix: Optional sealed+switch taxonomy.

---

## 5. SOLID Principles

### [ARCH-014] [High] Topology provisioning mixes declare + destructive migration (SRP)
- Location: `ServiceQueueTopologyConfigurer` — Module: schema-messaging-core
- Review: Same component builds queues/bindings and deletes legacy queues. Default-on delete couples greenfield topology to migration hazard. (See ARCH-001 for operational risk.)
- Fix: Separate `LegacyTopologyDecommissioner`, off by default, ops runbook.

### [ARCH-015] [Medium] Publish-path exception types inconsistent
- Location: `EventPublisher#publish` vs `SchemaAwareMessageConverter#toMessage` — Module: schema-messaging-core
- Review: No TypeMapping: publisher → `IllegalStateException`; converter → `MessageConversionException`.
- Fix: One typed exception on both paths.

### [ARCH-016] [Medium] `SchemaType.AVRO` advertised then rejected
- Location: `SchemaType.java`; `IndexedEventMappings#toMapping` — Modules: event-contract-kit, schema-messaging-core
- Review: Enum + SPI invite second format; registrar hard-fails non-JSON.
- Fix: Remove `AVRO` until strategy ships, or register strategy + allow index load.

### [ARCH-018] [Low] `@BitsEventHandler` brand name in domain-agnostic core
- Location: `BitsEventHandler.java` — Module: schema-messaging-core
- Review: Core library named for product brand.
- Fix: Rename to neutral `@EventHandler` when productizing.

---

## 6. Object-Oriented Design

### [ARCH-019] [Medium] Near-duplicate `*PublisherTopology` classes
- Location: `OrderPublisherTopology` / `CustomerPublisherTopology` — Modules: order-contracts, customer-contracts
- Review: Same three `@Bean` TopicExchange methods; only routing constants differ. Same as QUAL-004.
- Fix: Generic helper in kit or codegen for exchange triple.

### [ARCH-020] [Medium] `TypeMappingSelection` simple-name tokens ambiguous across domains
- Location: `TypeMappingSelection#matchesAny` — Module: schema-messaging-core
- Review: Simple name alone can match wrong domain when names collide.
- Fix: Prefer FQCN/coords; reject ambiguous simple-name matches when >1 hits.

### [ARCH-021] [Medium] `inPackage` exact match only — nested event packages invisible
- Location: `IndexedEventMappings#inPackage`; `@RegisterEventMappings(...)` — Modules: event-contract-kit, order-contracts, customer-contracts
- Review: Exact package equals; subpackage events never register.
- Fix: Document flat package only, or support recursive package option.

### [ARCH-022] [Low] Controllers own DTO→event mapping (no application service)
- Location: `OrderController`, `CustomerController` — Module: producer-service
- Review: Mapping + publish inline; weak layering for growth.
- Fix: Thin application services when business rules appear.

### [ARCH-023] [Low] `SchemaMessagingConsumerAutoConfiguration` name vs always-on `RabbitAdmin`
- Location: `SchemaMessagingConsumerAutoConfiguration#rabbitAdmin` — Module: schema-messaging-core
- Review: Producer needs `RabbitAdmin`; lives in class named Consumer.
- Fix: Rename (e.g. `AmqpInfrastructureAutoConfiguration`).

---

## 7. Design Patterns

### [ARCH-024] [High] Custom listener factory skips Boot configurer (Factory mis-composition)
- Location: `ListenerConfiguration#rabbitListenerContainerFactory` — Module: schema-messaging-core
- Review: Pattern intended “shared factory” but breaks Boot knobs. Deduplicated operational detail under SB-001; kept here as architecture/pattern finding.
- Fix: `configurer.configure(...)` then overlay converter + advice.

### [ARCH-025] [Medium] Dual Jackson stacks (Strategy isolation tax)
- Location: `SchemaMessagingAutoConfiguration#objectMapper`; `JsonSchemaStrategy`; Boot 4 Jackson 3 for web — Module: schema-messaging-core (+ services)
- Review: Messaging isolates Jackson 2; platform uses Jackson 3 for REST. Two ecosystems on classpath.
- Fix: Migrate strategy/validator to Jackson 3 when tooling allows.

### [ARCH-026] [Low] Bean-name override vs selection are two patterns for one concern
- Location: `EventMappingRegistrar`; `TypeMappingSelectionProperties` — Modules: event-contract-kit, schema-messaging-core
- Review: Override by bean name vs filter by type/coords — cognitive load.
- Fix: Keep both; document clearly; avoid a third knob.

---

## 8. Performance Review

### [PERF-001] [High] INFO log on every serialize + publish
- Location: `SchemaAwareMessageConverter.toMessage`; `EventPublisher.publish` — Module: schema-messaging-core
- Review: Hot path sync logger I/O + string format per event. (Also SB-012 / QUAL-013.)
- Fix: `debug`/`trace` or sample/metric; INFO for failures only.

### [PERF-003] [Medium] Prefetch left at framework default 250
- Location: `rabbitListenerContainerFactory` — Module: schema-messaging-core
- Review: With concurrency=1, one slow handler can hold up to 250 unacked msgs.
- Fix: Prefetch ≈ concurrency × batch (often 1–10); re-test lag. Wire via Boot configurer (SB-001).

### [PERF-004] [Medium] Double Jackson materialization per message
- Location: `JsonSchemaStrategy.serialize` / `deserialize` — Module: schema-messaging-core
- Review: Intentional tree→validate→bytes/object; cost scales with payload × throughput. Overlaps QUAL-007 (dual schema validate).
- Fix: Keep for correctness; measure; optional validate-from-bytes / `validate-on-deserialize` knob.

### [PERF-005] [Medium] Health probe does 2×N blocking AMQP calls
- Location: `QueueDepthHealthIndicator.health` — Module: consumer-service
- Review: Each probe hits main+DLQ for every handled mapping; compose polls every 5s.
- Fix: Cache depths briefly, async probe, or reduce queue set / interval.

### [PERF-007] [Medium] Sync `RabbitTemplate.send` on HTTP thread
- Location: `EventPublisher.publish`; `OrderController` etc. — Modules: schema-messaging-core, producer-service
- Review: Request thread blocks on validate+serialize+broker. Same as QUAL-006.
- Fix: Async publish + 202, outbox, or VT (JDK-001) after measuring.

---

## 9. Memory Analysis

### [MEM-002] [Medium] Full stack string before 4KB truncate (DLQ path)
- Location: `EventConsumerSupport.populateFailureHeaders` / `stackTrace` / `truncate` — Module: schema-messaging-core
- Review: Poison/DLQ storms allocate full stack + UTF-8 copy then truncate.
- Fix: Stream/truncate while writing; avoid second full `getBytes` of untruncated string.

### [MEM-001] [Low] Schema bytes kept after compile
- Location: `LocalSchemaCatalog`; `JsonSchemaStrategy.warm` — Module: schema-messaging-core
- Review: Raw `byte[]` + compiled schema for ≤7 mappings — fine for POC.
- Fix: Drop raw content after successful `warm` if unused.

### [MEM-003] [Low] Startup clone of every schema resource
- Location: `LocalSchemaCatalog` (`bytes.clone()`) — Module: schema-messaging-core
- Review: Defensive copy doubles peak startup alloc once per mapping.
- Fix: Store `readAllBytes()` directly if mutation risk is none.

---

## 10. Garbage Collection Review

### [GC-001] [Medium] No JVM heap/GC flags (cannot tune from evidence)
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile` `ENTRYPOINT ["java", "-jar", "app.jar"]`
- Review: Zero `-Xmx` / `-XX:MaxRAMPercentage` / GC flags. Cannot conclude G1/ZGC fitness.
- Fix: Container memory limit + `-XX:MaxRAMPercentage=<N>`; GC logging; choose GC from measured data.

### [GC-002] [Medium] No container memory limits in compose
- Location: `docker-compose.yml` producer/consumer services
- Review: Without cgroup limit, ergonomics size heap from host RAM → OOM risk on constrained hosts.
- Fix: Set service memory limits alongside GC-001.

---

## 11. Concurrency Review

### [CONC-004] [Medium] Failure recover blocks listener thread
- Location: `DlxMessageRecoverer.recover`; `DlxRoutingAdvice.invoke` — Module: schema-messaging-core
- Review: Retry/DLQ publish blocks the consumer that already failed; with concurrency=1 that queue stops.
- Fix: Raise concurrency (SB-001); size channel cache; keep sync ACK unless redesigned.

### [CONC-002] [Low] `synchronized` memoization on health path
- Location: `HandledEventTypesCache.handledTypeMappings` — Module: schema-messaging-core
- Review: Correct lazy memoize; later calls still take monitor.
- Fix: Init in `afterSingletonsInstantiated`; return plain field.

### [CONC-003] [Low] Schema compile cache is concurrent-safe
- Location: `JsonSchemaStrategy` `ConcurrentHashMap.computeIfAbsent` — Module: schema-messaging-core
- Review: Not a defect — post-warm is map lookup only.
- Fix: None required.

---

## 12. Database Review

No application database layer in producer/consumer (confirmed: no JPA/JDBC/datasource in app modules). Postgres in `docker-compose.yml` is Apicurio Registry storage only.

**No issues found** for app persistence. Infra DB credentials/exposure covered under Ops (OPS-002).

---

## 13. API Design Review

### [API-002] [High] No HTTP idempotency on publish endpoints
- Location: `OrderController`, `CustomerController` — Module: producer-service
- Review: Retried POST create → new UUID → duplicate events. No `Idempotency-Key` / dedup store.
- Fix: Client-supplied id and/or `Idempotency-Key`; document at-least-once + client responsibility if staying POC-only.

### [API-001] [Medium] Create APIs return 201 with empty body; IDs never returned
- Location: `OrderController`, `CustomerController` — Module: producer-service
- Review: Client cannot learn `orderId` / `customerId` for follow-up ship/cancel/fulfill.
- Fix: Return created id (body or `Location`).

### [API-004] [Medium] REST Bean Validation intentionally disabled; request DTOs unconstrained
- Location: `NoBeanValidationWebMvcConfiguration`; request records in controllers — Modules: producer-service, consumer-service
- Review: HTTP layer does not validate before publish; schema is authority. Late/messaging-coupled 400s for REST clients.
- Fix: `@Valid` + provider if REST is real API; else document as internal demo API.

### [API-005] [Low] Command endpoints misuse HTTP 201 Created
- Location: ship/cancel/fulfill/address/tier endpoints — Module: producer-service
- Review: Event publish is not REST resource create; poison uses 202 correctly.
- Fix: Use 202 (or 204) for command/publish.

### [API-007] [Low] No API catalog / versioning / OpenAPI
- Location: Controllers under `/api/orders`, `/api/customers` — Module: producer-service
- Review: Demo POST-only surface; no springdoc/OpenAPI.
- Fix: Add OpenAPI + `/api/v1` if REST leaves POC.

---

## 22. Docker & Kubernetes

### [OPS-001] [High] No Kubernetes / Helm / Kustomize manifests
- Location: repo-wide — zero Deployment/Service/Ingress/Helm/Kustomize
- Review: Docker Compose only. Blocks cluster deploy (probes, resources, secrets, rolling updates).
- Fix: Add K8s/Helm with Deployments, Services, ConfigMaps/Secrets, PDB/HPA; split liveness/readiness.

### [OPS-004] [High] Containers run as root; no security hardening
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile` — no `USER`; no `.dockerignore`
- Review: `eclipse-temurin:25-jre` + apt curl + `java -jar` as root.
- Fix: Non-root `USER`; `.dockerignore`; prefer distroless/JRE without shell.

### [OPS-005] [Medium] Image tags not digest-pinned
- Location: `docker-compose.yml`; Dockerfiles `FROM eclipse-temurin:25-jre`
- Review: Floating tags; rebuilds can pull different digests.
- Fix: Pin `@sha256:…` for prod/CI images.

### [OPS-008] [Medium] Dockerfiles are host-jar copy only
- Location: `docker-compose.yml`; both Dockerfiles `COPY target/*.jar`
- Review: Images assume prebuilt host Maven jars; not reproducible CI→registry pipeline.
- Fix: Multi-stage Dockerfile or CI build/push with immutable tags.

### [OPS-009] [Medium] Consumer fat-jar bound to `verify` — package footgun
- Location: `consumer-service/pom.xml` (`repackage` phase `verify`); compose comment suggests `package`/`install`
- Review: `mvn -pl consumer-service package` can leave non-repackaged jar for Docker COPY.
- Fix: Align phases or always run through `verify`/`install` for consumer images.

### [OPS-014] [Low] RabbitMQ service lacks `restart` policy used by peers
- Location: `docker-compose.yml` `rabbitmq` vs `restart: unless-stopped` on other services
- Review: Broker can stay down after host reboot while dependents restart.
- Fix: Add `restart: unless-stopped` (and resource limits).

---

## 23. Cloud Readiness

### [OPS-010] [Medium] No cloud / IaC / managed-service wiring
- Location: no Terraform/Pulumi/CloudFormation; README/compose are local Docker only
- Review: No VPC, secrets manager, managed RabbitMQ/Postgres/registry story.
- Fix: Document target cloud topology; IaC + secret injection; externalize URLs fully.

### [OPS-002] [High] Compose publishes infra with weak/default credentials
- Location: `docker-compose.yml`; both `application.yml` default `guest`/`guest`
- Review: Postgres/Apicurio `apicurio`/`apicurio`; RabbitMQ default guest; AMQP/management/DB/registry host-published.
- Fix: Secrets via env/files; non-guest creds; do not publish DB/broker/registry to host outside dev; no insecure defaults for prod.

---

## 24. Production Readiness

### [OPS-003] [High] Docker Compose deploy keeps demo poison endpoint on
- Location: `producer-service/.../application.yml`; `docker-compose.yml` producer env
- Review: Compose sets only `RABBITMQ_HOST` — no `SPRING_PROFILES_ACTIVE=prod` → poison stays on. (See ARCH-003.)
- Fix: `SPRING_PROFILES_ACTIVE=prod` or `events.demo.poison-endpoint=false` in compose/prod images.

### [OPS-006] [Medium] No graceful shutdown / lifecycle timeout config
- Location: both `application.yml` — no `server.shutdown` / lifecycle timeout
- Review: Default stop can cut in-flight HTTP/AMQP work.
- Fix: `server.shutdown=graceful` + lifecycle timeout; tune listener drain.

### [OPS-007] [Medium] Actuator health unsuitable as sole K8s/compose probe
- Location: `QueueDepthHealthIndicator` (DOWN if any DLQ depth > 0); compose healthchecks hit `/actuator/health`
- Review: DLQ backlog → composite DOWN → restart consumer that should drain DLQ. No liveness/readiness groups.
- Fix: Liveness = process up; readiness = broker reachable; DLQ depth as metric/alert only.

### [OPS-011] [Medium] CI: Failsafe ITs not on PRs; schema gates need self-hosted registry
- Location: `.github/workflows/build-and-test.yml`; schema-compat/register/bootstrap workflows
- Review: PRs get unit tests only; compat/register depend on self-hosted Apicurio runner.
- Fix: Run `verify` on PR or nightly required; Apicurio as CI service container or remote registry with auth.

---

## 26. Dependency Review

### [OPS-012] [Medium] No dependency/CVE automation
- Location: no Dependabot/Renovate; no OWASP/Snyk/Trivy in pom or workflows
- Review: Versions pinned manually — good for determinism, no patch pipeline.
- Fix: Dependabot/Renovate + periodic CVE scan (deps + images).

### [OPS-016] [Low] Maven Wrapper present and pinned (residual)
- Location: `.mvn/wrapper/maven-wrapper.properties`; parent BOM import
- Review: Wrapper committed (good). Testcontainers not via BOM import in `dependencyManagement`.
- Fix: Optionally import Testcontainers BOM.

---

## 27. Build Review

### [OPS-013] [Low] Enforcer only bans module edges — no release/Java/Maven baseline rules
- Location: parent `pluginManagement`; module `bannedDependencies` executions; version `0.1.0-SNAPSHOT`
- Review: Architecture bans present; no parent `requireJavaVersion` / `requireMavenVersion` / `requireReleaseDeps`.
- Fix: Parent enforcer with Java/Maven bounds; drop SNAPSHOT for release artifacts.

### [OPS-015] [Low] Workflows omit explicit `permissions:`
- Location: all five `.github/workflows/*.yml`
- Review: Rely on default `GITHUB_TOKEN` scope.
- Fix: Least privilege per workflow (`contents: read`, etc.).

---

## 25. Code Quality

### [QUAL-007] [Medium] Dual JSON-Schema validation; consume toggle not config-bound
- Location: `JsonSchemaStrategy`; `SchemaMessagingAutoConfiguration` — Module: schema-messaging-core
- Review: Produce validates; consume validates again; no yml knob (`validateOnDeserialize` always true in wiring).
- Fix: Expose `events.schema.validate-on-deserialize` (default true); benchmark before flipping.

### [QUAL-009] [Low] Magic `512` for failure-message truncate
- Location: `EventConsumerSupport.java` — Module: schema-messaging-core
- Review: Stack limit named; failure-message length bare literal.
- Fix: Named constant.

### [QUAL-010] [Low] Silent `long`→`int` TTL cast
- Location: `EventTopologyFactory.java` (`.ttl((int) ttlMs)`) — Module: event-contract-kit
- Review: Oversized config truncates without `Math.toIntExact`.
- Fix: `Math.toIntExact(ttlMs)` or reject at bind time.

### [QUAL-014] [Low] No `TODO`/`FIXME` in Java; tech debt in comments/docs
- Location: listeners / README / CLAUDE.md POC shortcuts
- Review: Source clean of TODO markers; production gaps documented as POC shortcuts.
- Fix: Track POC shortcuts as explicit backlog before prod promotion.

---

## 28. Maintainability

### [QUAL-003] [High] At-least-once claimed; no consumer dedup; publish path not durable
- Location: `OrderEventListener` / `CustomerEventListener` comments; README / CLAUDE.md — Modules: consumer-service, docs
- Review: Honest at-least-once + no dedup documented; combined with ARCH-002, network-layer delivery is not enforced; redelivery can double-apply.
- Fix: Message id + durable dedup (or handler idempotency); pair with publisher confirms.

### [QUAL-008] [Medium] Legacy queue delete stays default-on forever
- Location: `ServiceQueueTopologyAutoConfiguration` — Module: schema-messaging-core
- Review: Migration side-effect on every consumer boot; README admits message loss on delete. (Operational detail of ARCH-001.)
- Fix: Time-box / default false after cutover; drain-safe delete.

---

## 29. Scalability Assessment

**Cannot quantify msgs/s or p99 without load tests.** Evidence-based bottlenecks:

| Scale | Likely behavior |
|-------|-----------------|
| ~100 concurrent / low event rate | Likely OK for log-only handlers; sync publish + schema validate fine. |
| ~1k | Ceiling: 1 thread/queue (SB-001), HTTP workers blocked on validate+send (PERF-007), dual schema CPU (QUAL-007). |
| ~10k | Needs concurrency/prefetch + replicas, publisher confirms/outbox, handler idempotency (QUAL-003), optional consume-validation toggle, Tomcat/channel sizing. |

**Primary bottlenecks (ordered):** (1) 1 consumer/queue, (2) sync publish on HTTP without confirms, (3) dual schema validation, (4) no dedup under redelivery.

---

## 4. Prioritized Fix List

1. [ARCH-002][High] Publisher confirms/returns/mandatory — `EventPublisher.java` — fail HTTP until broker ack
2. [ARCH-001][High] Legacy queue delete default-off / if-empty / one-shot — `ServiceQueueTopologyAutoConfiguration.java`
3. [ARCH-003][High] Poison endpoint default `false` + compose `prod` — `application.yml` / `docker-compose.yml`
4. [SB-001][High] Wire `SimpleRabbitListenerContainerFactoryConfigurer` + concurrency/prefetch — `SchemaMessagingConsumerAutoConfiguration.java`
5. [OPS-002][High] Remove guest/default secrets; stop host-publishing infra — `docker-compose.yml` / app yml
6. [OPS-004][High] Non-root containers + `.dockerignore` — Dockerfiles
7. [OPS-001][High] Add K8s/Helm manifests with probe split — new deploy assets
8. [OPS-003][High] Force prod/demo flag off in compose images — `docker-compose.yml`
9. [API-002][High] HTTP idempotency / client-supplied ids — producer controllers
10. [QUAL-003][High] Consumer/handler idempotency + message ids — listeners + publisher
11. [ARCH-014][High] Split legacy decommissioner from topology declare — `ServiceQueueTopologyAutoConfiguration.java`
12. [ARCH-024][High] Same fix as SB-001 (factory composition) — listener factory
13. [PERF-001][High] Drop hot-path INFO to DEBUG — converter + publisher
14. [ARCH-007][Medium] Broaden `GlobalExceptionHandler` + ProblemDetail — producer-service
15. [API-001][Medium] Return created ids on 201 — controllers
16. [SB-004][Medium] Validated `@ConfigurationProperties` for retry tiers — core
17. [OPS-006][Medium] Graceful shutdown config — both `application.yml`
18. [OPS-007][Medium] Split liveness/readiness; DLQ not DOWN — health indicator + actuator groups
19. [PERF-003][Medium] Tune prefetch with concurrency — listener factory / yml
20. [GC-001][Medium] JVM MaxRAMPercentage + GC logging — Dockerfiles
21. [QUAL-007][Medium] Config knob for validate-on-deserialize — auto-config
22. [OPS-011][Medium] Run Failsafe/`verify` on PR; portable Apicurio in CI — workflows
23. [OPS-012][Medium] Dependabot + CVE scanning — repo automation
24. [JDK-001][Medium] Enable virtual threads after SB-001 — service yml
25. [ARCH-019][Medium] Deduplicate `*PublisherTopology` shells — contracts/kit
26. [API-005][Low] Use 202 for command publish endpoints — controllers
27. [ARCH-013][Low] Enforcer-ban `schema-gen-tools` on services — service POMs
28. [OPS-015][Low] Explicit workflow `permissions:` — `.github/workflows`
29. [QUAL-009][Low] Named constant for failure-message truncate — `EventConsumerSupport`
30. [SB-010][Low] Deduplicate `NoBeanValidationWebMvcConfiguration` — services
