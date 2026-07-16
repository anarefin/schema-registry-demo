# 01 — Defend tolerant reader (factory-internal mapper + QUAL-003 E2E)

**What to build:** Messaging deserialization owns a dedicated tolerant `ObjectMapper` built inside the `JsonSchemaStrategy` factory — never exposed as a bean and never injected from the app context. An application `@Bean ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=true` must not displace messaging. An auto-config / Failsafe-style test proves a payload with an unexpected field still deserializes successfully (no `SchemaValidationException`, no DLQ path).

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `jsonSchemaStrategy()` constructs its own tolerant mapper (`FAIL_ON_UNKNOWN_PROPERTIES=false`, modules registered, dates not as timestamps, `NON_NULL` inclusion); it takes no `ObjectMapper` parameter
- [ ] Messaging never injects the shared / app `ObjectMapper`; the existing `@ConditionalOnMissingBean(ObjectMapper.class)` fallback remains only for non-messaging Jackson 2 needs
- [ ] E2E/auto-config test: `@Primary` strict app `ObjectMapper` + event payload with an extra field → successful consume via real converter/listener path
- [ ] Unit tests still pass; tolerant-reader defense is not solely hand-set flags on test mappers
- [ ] `./mvnw -pl schema-messaging-core test` (and any new IT under `verify` if used) passes
