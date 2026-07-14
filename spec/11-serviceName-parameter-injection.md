# 11 — Replace field-injected `serviceName` with parameter injection

**Severity:** Low · **Finding:** SPRING-003 · **Type:** AFK

## What to build

`SchemaMessagingConsumerAutoConfiguration:49` reads the service name via field injection
(`@Value private String serviceName`). Field injection in a configuration class is harder to test and
inconsistent with the constructor-injection style used elsewhere. It also hard-fails context startup
with an opaque error if `spring.application.name` is ever unset.

Inject `serviceName` as a `@Bean` method parameter (as `ServiceQueueTopologyAutoConfiguration` and
`QueueDepthHealthIndicator` already do), and give it a sensible default —
`${spring.application.name:unknown-service}` — or fail with a clear message.

## Acceptance criteria

- [ ] `serviceName` is injected as a `@Bean` method parameter, not a `@Value` field.
- [ ] A default is supplied (`${spring.application.name:unknown-service}`) or startup fails with a clear, actionable message when unset.
- [ ] Injection style is consistent with `ServiceQueueTopologyAutoConfiguration` / `QueueDepthHealthIndicator`.
- [ ] Existing behavior preserved when `spring.application.name` is set; covered by a test.

## Blocked by

None — can start immediately.
