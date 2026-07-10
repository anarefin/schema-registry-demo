# ADR-0004: Local Classpath Schema Validation (Remove Runtime Apicurio Dependency)

## Status

Accepted.

## Context

`schema-messaging-core`'s `SchemaAwareMessageConverter` called `SchemaResolver`, which called
`ApicurioClient`, over HTTP to Apicurio Registry on **every produce and every consume** — backed
by a Caffeine cache with TTL/refresh-after-write and a stale-serving safety net for registry
outages (`CachePreWarmer` warmed it at startup; `StartupSchemaValidator` could optionally fail
fast if `apicurio.auto-register=OFF`; `RegistryHealthIndicator` exposed registry reachability at
`/actuator/health`).

But the JSON Schemas being fetched were already fully known at build time. [ADR-0001](0001-code-first-schema-generation.md)
and [ADR-0003](0003-contracts-own-schema-generation.md) established that `schema-gen-tools`
generates each event's schema from its code-first Java record and commits it into that domain's
`*-contracts` module (`order-contracts/src/main/resources/schemas/*.schema.json`,
`customer-contracts/src/main/resources/schemas/*.schema.json`). Those files already land on
producer-service's and consumer-service's runtime classpath as ordinary JAR resources, since both
services depend on the contracts modules. The registry round-trip was fetching, over the network,
content already sitting in the JVM's own classpath — making RabbitMQ message throughput and
service startup dependent on Apicurio's availability and latency for no benefit at runtime.

## Decision

Remove all JVM-runtime calls to Apicurio Registry from `schema-messaging-core`, producer-service,
and consumer-service.

- **`LocalSchemaCatalog`** (`schema-messaging-core/.../core/schema/`) replaces `SchemaResolver` +
  `ApicurioClient` + `CachePreWarmer` + `StartupSchemaValidator` + `RegistryHealthIndicator`. It
  loads every registered `TypeMapping`'s schema bytes from the classpath **eagerly, at bean
  construction**, and throws synchronously if a resource is missing — aborting Spring context
  refresh rather than serving stale content or failing on the first message of that type. No TTL,
  no refresh, no network calls, no stale-serving: the set is small (six schemas), fixed, and
  immutable for the life of the JVM.
- Malformed schema *content* (not just a missing file) is also caught at startup: a new
  `SerializationStrategy.warm(ResolvedSchema)` SPI hook is invoked once per mapping from
  `SchemaAwareMessageConverter`'s existing constructor fail-fast loop, forcing `JsonSchemaStrategy`
  to eagerly compile (and cache) each schema.
- The classpath resource path for a given event type is derived at runtime from its simple class
  name via a kebab-case naming convention (`OrderCreated` → `schemas/order-created.schema.json`).
  `schema-gen-tools` derives its output filename the same way. Because `schema-gen-tools` and
  `schema-messaging-core` must stay dependency-free of each other, the naming function is
  duplicated (not shared via a new module), with a parity unit test in each module.
- `X-Schema-GlobalId` and `X-Schema-Version` headers are dropped — nothing computes a real
  Apicurio globalId locally, and there is no "version" concept left once there is exactly one
  schema per build. `SchemaCoordinates` shrinks to `(groupId, artifactId)`, reused purely as a
  local lookup key.
- Schema version pinning (`schema.{orders,customers}.pinned-version`) is removed entirely — a
  locally-baked JAR has exactly one version of each schema by construction, so there is nothing
  left to pin between.
- **Apicurio Registry is not removed from the project.** It remains the CI/governance tool:
  `apicurio-registry-maven-plugin`'s `register` goal and the `compat-check` Maven profile
  (build-time only, a Maven-plugin code path entirely separate from the JVM runtime classes above)
  continue to register schemas and gate incompatible changes in CI, unchanged.
  `docker-compose.yml` keeps running Apicurio for that flow, but it is no longer a startup
  dependency for producer-service or consumer-service.

## Consequences

- Producer and consumer no longer need Apicurio reachable to start or process messages — RabbitMQ
  is the only runtime infrastructure dependency left for the message-processing path.
- A missing or malformed schema file now fails fast at application startup (Spring context refresh
  aborts with a clear error) instead of being silently served stale from cache or failing
  message-by-message at first use.
- The whole registry-outage retry/stale-serving story disappears: `RegistryUnavailableException`
  is deleted, and there is no runtime network call left in the schema-resolution path that can
  fail transiently. `DlxRoutingIT`'s registry-down retry scenario was removed with no substitute —
  there is no other transient failure mode left in the runtime schema path once schema presence is
  guaranteed at startup.
- **Lost capability: hot-swapping a schema without a redeploy.** Previously an operator could push
  a new compatible schema to the registry and, after cache TTL/refresh, producers/consumers would
  pick it up live. Now a schema change requires a rebuild and redeploy of the consuming service —
  the same as any other code-first artifact change.
- **Lost capability: runtime schema version pinning.** Pinning only made sense against a registry
  that could serve multiple live versions; a locally-baked JAR has exactly one version of each
  schema by construction.
- `schema-messaging-core` no longer depends on the Apicurio Java SDK or Caffeine (the only
  remaining cache — `JsonSchemaStrategy`'s compiled-schema cache — is a plain, fixed-size
  `ConcurrentHashMap`, since the set of schemas is immutable and populated once at startup).
