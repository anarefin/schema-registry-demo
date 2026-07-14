# 05 — Enable virtual threads on Java 25

**Severity:** Medium · **Finding:** JDK-001 · **Type:** AFK

## What to build

The stack is Java 25 with a blocking web tier and blocking (`SimpleMessageListenerContainer`) AMQP
listeners — the exact profile that benefits most from virtual threads — yet
`spring.threads.virtual.enabled` is unset. A cheap, well-supported scalability lever is being left on
the table.

Enable virtual threads in both services (Spring Boot 4 wires virtual-thread executors for Tomcat and
the listener containers) and validate under load, watching for pinning on `synchronized` blocks on
hot paths.

## Acceptance criteria

- [ ] `spring.threads.virtual.enabled=true` set in producer and consumer `application.yml`.
- [ ] Verified Tomcat and listener containers use virtual-thread executors.
- [ ] Load validation notes captured; no regression from `synchronized` pinning on hot paths
      (e.g. `HandledEventTypesCache.handledTypeMappings()`, which runs once at startup).

## Blocked by

None — can start immediately.
