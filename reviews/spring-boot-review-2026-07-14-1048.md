# Spring Boot Enterprise Production Readiness Review

_Generated 2026-07-14 10:48 · Reviewer framing: Principal Software Architect / JVM Performance Engineer / Spring Boot Expert / Security Architect / Distributed Systems Expert / Production Reliability Engineer._

## 1. Stack Summary

| Attribute | Detected value |
|-----------|----------------|
| Spring Boot | **4.1.0** (BOM import in parent `pom.xml`, not `spring-boot-starter-parent`) |
| JDK | **25** (`<maven.compiler.release>25</maven.compiler.release>`, `<release>25</release>`, toolchains v25) |
| Language | Java only |
| Build tool | Maven (committed Maven Wrapper `./mvnw`) |
| Layout | Multi-module — 7 modules: `schema-messaging-core`, `event-contract-kit`, `order-contracts`, `customer-contracts`, `producer-service`, `consumer-service`, `schema-gen-tools` |
| Run mode | Sequential inline (small codebase: 73 main classes / 40 test classes) |

**Areas run:** Architecture & Design (1) · Spring Boot & JDK (2) · Ops, Cloud & Build (7).
**Areas skipped:** Performance & Runtime (3) · Data & API (4) · Security & Resilience (5) · Observability & Testing (6) · Quality & Scalability (8). Findings that would normally sit in a skipped area (e.g. broker credentials, publisher confirms) are reported here only where they intersect the selected areas (production readiness / cloud), and are scoped accordingly.

---

## 2. Executive Summary

This is a well-architected messaging POC with unusually disciplined module boundaries: the `core ↛ contracts` and `contracts ↛ core` rules are machine-enforced with `maven-enforcer-plugin`, versions are single-sourced in the parent POM, and the Strategy/Factory patterns are applied cleanly (`SerializationStrategy` SPI, `EventTopologyFactory`). The design is genuinely strong at the module and pattern level. Production readiness, however, is not yet there: the container images run as **root** with no graceful shutdown or container-aware JVM settings, the images are built non-reproducibly from a host-built jar, actuator health leaks internals unauthenticated, and a health indicator that flips to DOWN on DLQ backlog can crashloop a pod. A demo "poison" endpoint is compiled into the production controller.

**Verdict: NO-GO for production** — driven by one High (containers run as root) plus a cluster of Medium production-readiness gaps (graceful shutdown, non-reproducible build, health-probe crashloop trap, unauthenticated health details, insecure broker defaults). None are architectural rewrites; all are concrete, well-scoped fixes.

The project itself is candid that it is a POC ("all planned POC phases are done"), so several findings are "before this leaves POC status," not defects in POC scope.

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 1 |
| Medium | 11 |
| Low | 5 |

---

## 3. Findings

## Critical

None.

## High

### [OPS-001] [High] Container images run as root with a full JRE base
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile` — Module: producer-service / consumer-service
- Review: Both Dockerfiles `FROM eclipse-temurin:25-jre`, install packages via `apt-get`, and `ENTRYPOINT ["java","-jar","app.jar"]` with no `USER` directive, so the JVM runs as UID 0. A container escape or RCE in the app then executes as root; this is a standard enterprise container-hardening failure and is commonly a release blocker.
- Fix: Add a non-root user and drop to it, e.g.:
  ```dockerfile
  FROM eclipse-temurin:25-jre
  RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
      && groupadd -r app && useradd -r -g app app
  WORKDIR /app
  COPY --chown=app:app target/producer-service-*.jar app.jar
  USER app
  EXPOSE 8081
  ENTRYPOINT ["java","-jar","app.jar"]
  ```
  Prefer a slim/distroless runtime base for a smaller attack surface; if curl is only needed for the healthcheck, use Spring Boot's own health probe or a shell-free check instead of installing curl.

## Medium

### Architecture & Design

### [ARCH-001] [Medium] Demo "poison" endpoint compiled into the production controller
- Location: `producer-service/src/main/java/com/example/producer/controller/OrderController.java:119` (`publishPoison`) — Module: producer-service
- Review: `POST /api/orders/poison` is a live, unauthenticated endpoint in the production artifact that publishes malformed bytes straight to the orders exchange (bypassing the converter), guarded only by a Javadoc "FOR DEMO/TEST USE ONLY". In production anyone who can reach the service can flood every consumer's DLQ on demand — an availability/abuse vector.
- Fix: Gate it behind a profile so it is absent from prod, e.g. annotate the controller (or a dedicated demo controller) with `@Profile("demo")`, or move the poison path into a test fixture. At minimum require it be explicitly enabled via `@ConditionalOnProperty("demo.poison.enabled")`.

### [ARCH-002] [Medium] Consume path never re-validates payloads — malformed-but-parseable messages NPE in handlers
- Location: `schema-messaging-core/.../converter/SchemaAwareMessageConverter.java:103` (`fromMessage`) and `consumer-service/.../listener/OrderEventListener.java:44` (`onOrderFulfilled`) — Module: schema-messaging-core / consumer-service
- Review: `fromMessage` reads headers and deserializes but does **not** validate the body against the JSON Schema (only `toMessage` validates). A message that is valid JSON but structurally wrong (older/rogue producer, or the `/poison`-style path) deserializes into a record with `null` nested fields; `onOrderFulfilled` then calls `event.buyer().displayName()` / `event.shipping().city()` / `event.payment().method()` and throws NPE. That NPE is a plain `RuntimeException`, so `EventConsumerSupport.classify()` treats it as **transient** → full retry ladder (5s/30s/5m) before the DLQ, i.e. a retry storm for a permanently-bad message.
- Fix: Validate on consume as well (call the strategy's schema validation in `fromMessage` before returning), so a non-conformant payload becomes a `SchemaValidationException` (`PermanentFailure` → straight to DLQ). Alternatively, defend the handlers against null nested objects. Validating symmetrically is the design-consistent fix and closes the retry-storm window.

### Spring Boot & JDK

### [SPRING-001] [Medium] Rabbit listener container uses default single consumer / default prefetch
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java:72` (`rabbitListenerContainerFactory`) — Module: schema-messaging-core
- Review: The `SimpleRabbitListenerContainerFactory` sets the converter, requeue-reject, and advice chain but leaves `concurrentConsumers`, `maxConcurrentConsumers`, and `prefetchCount` at defaults (1 consumer, prefetch 250). For a "high-scale" consumer this caps per-service throughput at a single thread per queue and gives no back-pressure tuning.
- Fix: Expose these as bound properties and set sensible defaults, e.g.
  ```java
  factory.setConcurrentConsumers(concurrency);       // e.g. 2
  factory.setMaxConcurrentConsumers(maxConcurrency);  // e.g. 10
  factory.setPrefetchCount(prefetch);                 // e.g. 20–50
  ```
  Tune per event type if handler cost varies.

### [SPRING-002] [Medium] Global no-op MVC Validator silently disables all Bean Validation
- Location: `producer-service/.../config/NoBeanValidationWebMvcConfiguration.java:29` and `consumer-service/.../config/NoBeanValidationWebMvcConfiguration.java` — Module: producer-service / consumer-service
- Review: `getValidator()` returns a `Validator` whose `supports()` always returns `false`, disabling Spring MVC Bean Validation process-wide. This is deliberate today (JSON Schema owns validation), but it is a latent footgun: any future controller that adds `@Valid @RequestBody` will compile, appear validated, and silently validate nothing.
- Fix: Keep the intent explicit and narrow. Rather than a global no-op validator, document the decision and, if a real BV provider is ever added, remove this override. If the only goal is to silence the `OptionalValidatorFactoryBean` INFO log, the `ValidationAutoConfiguration` exclusion on the `@SpringBootApplication` already covers it — reconsider whether the no-op validator is still needed at all.

### Ops, Cloud & Build

### [OPS-002] [Medium] No graceful shutdown or container-aware JVM configuration
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile`, both `application.yml` — Module: producer-service / consumer-service
- Review: ENTRYPOINT is a bare `java -jar app.jar` with no `-XX:MaxRAMPercentage`/`-XX:+UseContainerSupport` tuning and no `server.shutdown=graceful` / `spring.lifecycle.timeout-per-shutdown-phase`. On SIGTERM (rolling deploy, scale-down) the Rabbit listener can be killed mid-handler, dropping in-flight work; under a cgroup memory limit the JVM may size the heap against host RAM and OOM-kill.
- Fix: Add to each `application.yml`:
  ```yaml
  server:
    shutdown: graceful
  spring:
    lifecycle:
      timeout-per-shutdown-phase: 30s
  ```
  and set JVM flags via `JAVA_TOOL_OPTIONS`/`ENTRYPOINT`, e.g. `-XX:MaxRAMPercentage=75.0`. Ensure the Rabbit container's `shutdownTimeout` allows in-flight messages to finish.

### [OPS-003] [Medium] Non-reproducible image build — Dockerfile copies a host-built jar
- Location: `producer-service/Dockerfile:5`, `consumer-service/Dockerfile:5`, `docker-compose.yml:73-97` — Module: producer-service / consumer-service
- Review: The image does `COPY target/producer-service-*.jar app.jar`, so it only works after a prior host `./mvnw install`, and the image contents are whatever happened to be in `target/`. This is not reproducible or CI-friendly, and the `*.jar` glob can silently pick up a stale/duplicate jar. There is also no `.dockerignore`, so the entire module dir (incl. `src`, `target`) enters the build context.
- Fix: Use a multi-stage build that compiles inside the image (build stage with Maven + JDK 25 → runtime stage copies the exact jar), or pin the jar name explicitly. Add a `.dockerignore` excluding `target/` (except the final jar), `src`, and `.git`. If keeping the host-jar approach for the POC, at least pin the exact artifact name to avoid the glob.

### [OPS-004] [Medium] Actuator health exposes internal details unauthenticated
- Location: `producer-service/src/main/resources/application.yml:19-22`, `consumer-service/src/main/resources/application.yml:19-22` — Module: producer-service / consumer-service
- Review: `management.endpoint.health.show-details: always` (+ `show-components: always`) with no security starter present means `/actuator/health` returns full component detail — including `QueueDepthHealthIndicator`'s per-queue depths and broker error strings — to any unauthenticated caller, leaking internal topology and failure detail.
- Fix: Set `show-details: when-authorized` (and `show-components: when-authorized`) and add authn/authz on the actuator context, or bind the management port to an internal-only interface. Keep the liveness/readiness probe groups detail-free.

### [OPS-005] [Medium] Queue-depth health indicator can crashloop a pod on DLQ backlog
- Location: `consumer-service/src/main/java/com/example/consumer/health/QueueDepthHealthIndicator.java:77` — Module: consumer-service
- Review: `health()` returns `Health.down()` whenever **any** DLQ has messages. Because this indicator joins the default `/actuator/health` group, wiring that endpoint to a Kubernetes liveness probe (or a compose autoheal) makes a single stuck DLQ message flip the pod to DOWN → the orchestrator restarts it → the restart does nothing to drain the DLQ → crashloop. A DLQ with messages is an alerting condition, not a "kill the process" condition.
- Fix: Keep this indicator **off** the liveness path. Register liveness/readiness groups explicitly (`management.endpoint.health.group.liveness.include=livenessState`, `...group.readiness.include=readinessState,rabbit`) and expose DLQ depth via a separate, non-probe endpoint or a metric/alert instead of `Health.down()`. If DLQ backlog must affect an endpoint, put it in a custom group that nothing restarts on.

### [OPS-006] [Medium] Insecure broker defaults — guest/guest fallback and plaintext AMQP
- Location: `producer-service/src/main/resources/application.yml:4-8`, `consumer-service/src/main/resources/application.yml:4-8`, `docker-compose.yml:62-71` — Module: producer-service / consumer-service
- Review: RabbitMQ credentials default to `guest`/`guest` and the connection is plaintext AMQP (port 5672, no TLS). Credentials are externalized via `${RABBITMQ_USERNAME}`/`${RABBITMQ_PASSWORD}` (good), but the insecure fallback plus no TLS means a misconfigured prod deploy silently runs with guest/guest over the wire.
- Fix: In prod require credentials with no insecure default (fail fast if unset), enable TLS (`spring.rabbitmq.ssl.enabled=true`), and provision a non-guest broker user with least-privilege permissions. Do not ship guest/guest as the effective default outside local dev.

### [OPS-008] [Medium] Publisher path has no publisher confirms/returns — silent message loss
- Location: `schema-messaging-core/.../publisher/EventPublisher.java:52` (`rabbitTemplate.send`) and `SchemaMessagingAutoConfiguration` (RabbitTemplate wiring) — Module: schema-messaging-core
- Review: `EventPublisher.publish` calls `rabbitTemplate.send(...)` with no publisher confirms or mandatory/return handling configured. If the broker is unavailable or rejects the publish (e.g. unroutable), the HTTP endpoint still returns 201 and the event is silently lost — a production reliability gap for an at-least-once system. (Reported here under production-readiness; deeper messaging-durability analysis belongs to the un-run Security & Resilience area.)
- Fix: Enable `spring.rabbitmq.publisher-confirm-type=correlated` and `spring.rabbitmq.publisher-returns=true`, set `RabbitTemplate` mandatory, and register confirm/return callbacks so a nack/return surfaces as a failed publish (e.g. 5xx + retry/outbox) rather than a silent drop.

### Dependencies

### [DEP-001] [Medium] Two Jackson stacks on the runtime classpath (Jackson 2 + Jackson 3)
- Location: `schema-messaging-core/pom.xml:41-53` (explicit `com.fasterxml.jackson`), plus Spring Boot 4.1 `spring-boot-starter-json` (Jackson 3 `tools.jackson`) pulled transitively by `spring-boot-starter-web` — Module: schema-messaging-core / producer-service / consumer-service
- Review: The project deliberately runs Jackson 2 (`com.fasterxml`, with a hand-built `ObjectMapper` and `jackson-datatype-jsr310`) while Spring Boot 4.1's starter ships Jackson 3 (`tools.jackson`). This is documented and works, but keeping two serialization stacks on the classpath is duplicate-library tech debt: larger footprint, two mappers to reason about, and ongoing risk that Boot auto-config wires the "other" Jackson than the one the converter uses.
- Fix: Plan a migration to Jackson 3 (`tools.jackson`) so a single stack is present, letting Boot's `JacksonAutoConfiguration` own the `ObjectMapper` and dropping the manual Jackson-2 bean + jsr310 module. Track as a post-POC cleanup with a dependency-convergence check to prevent both from lingering.

### [OPS-007] [Medium] Legacy-queue decommission runs on every startup and can drop messages
- Location: `schema-messaging-core/.../config/ServiceQueueTopologyAutoConfiguration.java:90` (`decommissionLegacySharedDomainQueues`) — Module: schema-messaging-core
- Review: With `events.topology.decommission-legacy-queues=true` (the default), every consumer startup calls `rabbitAdmin.deleteQueue(...)` for the legacy shared-domain queue names. `deleteQueue` removes the queue even if it still holds messages, so any message that lands on a legacy queue during a mixed-version cutover is silently lost, and the destructive action re-runs on every deploy rather than once.
- Fix: Make this a one-shot, opt-in migration rather than a default-on every-boot action: default the flag to `false` after cutover, or use `rabbitAdmin.deleteQueue(name, true /*unused*/, true /*ifEmpty*/)` so a non-empty legacy queue is preserved and surfaced instead of purged.

## Low

### [ARCH-003] [Low] Controller request DTOs duplicate contract record fields with manual mapping
- Location: `producer-service/.../controller/OrderController.java:133-171`, `CustomerController.java:77-91` — Module: producer-service
- Review: Each `*Request` record mirrors its contract event record field-for-field and is mapped by hand in the controller. This is a reasonable API/contract boundary, but there is no compiler enforcement that the two stay in sync — adding a contract field silently leaves the request DTO behind.
- Fix: Acceptable as-is for a POC; if these DTOs proliferate, add a mapping test (or a mapper like MapStruct with unmapped-target failure) so drift between request DTO and contract record is caught at build time.

### [ARCH-004] [Low] `EventPublisher` redundantly re-resolves the TypeMapping and throws a different exception type
- Location: `schema-messaging-core/.../publisher/EventPublisher.java:43` — Module: schema-messaging-core
- Review: `publish()` resolves the `TypeMapping` via `findByJavaType` (to get exchange/routing key) and then `messageConverter.toMessage(...)` resolves the same mapping again for serialization — two lookups per publish. The two paths also disagree on the missing-mapping contract: `EventPublisher` throws `IllegalStateException` while the converter throws `MessageConversionException`.
- Fix: Resolve the mapping once and pass exchange/routing key through, or read them back from the converter result; align the missing-mapping exception type across both paths.

### [SPRING-003] [Low] Field injection via `@Value` on an auto-configuration class
- Location: `schema-messaging-core/.../config/SchemaMessagingConsumerAutoConfiguration.java:49` (`@Value private String serviceName`) — Module: schema-messaging-core
- Review: `serviceName` is field-injected with `@Value`, which is harder to test and mutable versus constructor/parameter injection. Other classes in the same codebase (e.g. `QueueDepthHealthIndicator`) already inject it as a constructor parameter.
- Fix: Inject `spring.application.name` as a `@Bean` method parameter where it's used (as the topology/registrar beans already do), removing the field.

### [SPRING-004] [Low] Virtual threads not enabled (Java 25 / Boot 4.1 modernization)
- Location: both `application.yml` — Module: producer-service / consumer-service
- Review: On Java 25 with Spring Boot 4.1, the blocking web tier (and optionally Rabbit listeners) can run on virtual threads for cheaper concurrency under I/O-bound load. Neither service sets `spring.threads.virtual.enabled`.
- Fix: Consider `spring.threads.virtual.enabled=true` for the web tier after load-testing; evaluate virtual-thread executors for listener containers. Optional — validate under representative load first.

### [OPS-009] [Low] No `.dockerignore`; healthcheck couples image to installed curl
- Location: `producer-service/Dockerfile`, `consumer-service/Dockerfile`, `docker-compose.yml:88-92` — Module: producer-service / consumer-service
- Review: Absent a `.dockerignore`, the whole module directory is sent as build context; and the compose healthcheck depends on curl being installed in the runtime image purely for probing.
- Fix: Add a `.dockerignore`. Consider a shell-free healthcheck (Spring Boot actuator probe via the orchestrator, or a tiny HTTP check) so the runtime image need not carry curl. (Overlaps OPS-001/OPS-003.)

---

## 4. Prioritized Fix List

1. [OPS-001][High] Container runs as root — `producer-service/Dockerfile`, `consumer-service/Dockerfile` — add non-root `USER` (and slim/distroless base).
2. [ARCH-001][Medium] Poison demo endpoint in prod controller — `OrderController.java:119` — gate behind `@Profile("demo")`/`@ConditionalOnProperty`.
3. [ARCH-002][Medium] No consume-side validation → handler NPE + retry storm — `SchemaAwareMessageConverter.java:103` — validate payload in `fromMessage`.
4. [SPRING-001][Medium] Single-consumer listener container — `SchemaMessagingConsumerAutoConfiguration.java:72` — set concurrency/prefetch.
5. [SPRING-002][Medium] Global no-op MVC Validator disables Bean Validation — `NoBeanValidationWebMvcConfiguration.java:29` — narrow/remove the override.
6. [OPS-002][Medium] No graceful shutdown / container JVM flags — Dockerfiles + `application.yml` — add `server.shutdown=graceful`, `MaxRAMPercentage`.
7. [OPS-003][Medium] Non-reproducible image (copies host jar) — Dockerfiles — multi-stage build + `.dockerignore` + pinned jar name.
8. [OPS-004][Medium] Health details exposed unauthenticated — both `application.yml` — `show-details: when-authorized` + actuator auth.
9. [OPS-005][Medium] DLQ-DOWN health can crashloop pod — `QueueDepthHealthIndicator.java:77` — keep off liveness path; use dedicated group/metric.
10. [OPS-006][Medium] Insecure broker defaults (guest/guest, no TLS) — `application.yml` + `docker-compose.yml` — require creds + enable TLS in prod.
11. [OPS-008][Medium] No publisher confirms → silent publish loss — `EventPublisher.java:52` — enable correlated confirms + returns + callbacks.
12. [DEP-001][Medium] Dual Jackson stacks (2 + 3) — `schema-messaging-core/pom.xml:41-53` — plan migration to Jackson 3.
13. [OPS-007][Medium] Legacy-queue delete on every boot can drop messages — `ServiceQueueTopologyAutoConfiguration.java:90` — one-shot/ifEmpty, default flag off post-cutover.
14. [ARCH-003][Low] Request DTO ↔ contract drift unenforced — `OrderController.java:133` — add mapping test / MapStruct.
15. [ARCH-004][Low] Redundant TypeMapping lookup + inconsistent exception — `EventPublisher.java:43` — resolve once, align exception type.
16. [SPRING-003][Low] `@Value` field injection on auto-config — `SchemaMessagingConsumerAutoConfiguration.java:49` — use constructor/param injection.
17. [SPRING-004][Low] Virtual threads not enabled — `application.yml` — evaluate `spring.threads.virtual.enabled=true` under load.
18. [OPS-009][Low] No `.dockerignore`; curl-coupled healthcheck — Dockerfiles — add `.dockerignore`, shell-free probe.

---

_Scope note: Performance, Data/API, Security & Resilience, Observability & Testing, and Quality & Scalability were not selected for this run and were not assessed; several genuinely important topics (broker durability/idempotency, metrics/tracing, test coverage of the failure taxonomy) live in those areas. Re-run those modules before a production go/no-go decision._
