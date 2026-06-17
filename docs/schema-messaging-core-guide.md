# schema-messaging-core — A Beginner's Guide

This guide explains every part of the `schema-messaging-core` library from first principles.
You don't need any prior knowledge of Apicurio Registry — we'll build up the full picture step by step.

---

## 1. The Problem and the Players

### 1.1 Why do services need a shared language?

Imagine two colleagues sending each other voice messages. If one speaks English and the
other only understands French, the message arrives but means nothing. The same problem
exists between software services.

When a **producer service** publishes a message (e.g., "an order was created") to a
message broker like RabbitMQ, a **consumer service** must know *exactly* what fields to
expect. If the producer silently renames a field, the consumer's code crashes at runtime.

A **Schema Registry** solves this by acting as the single source of truth for the
structure (schema) of every message type. Before sending, the producer validates its
payload against the registered schema. Before processing, the consumer does the same.
If the schema changes in a breaking way, the registry rejects the change and CI fails.

### 1.2 What is Apicurio Registry?

[Apicurio Registry](https://www.apicur.io/registry/) is an open-source schema registry
that stores, versions, and enforces compatibility rules for schemas. Think of it as a
Git repository, but for message schemas.

Key Apicurio concepts used in this project:

| Term | What it means |
|------|---------------|
| **Group** | A namespace for related schemas, e.g. `events.orders` |
| **Artifact** | One schema definition within a group, e.g. `OrderCreated` |
| **Version** | A numbered snapshot of an artifact (1, 2, 3…) |
| **Global ID** | A unique integer Apicurio assigns to every version across all groups; the fastest lookup key |
| **Compatibility Rule** | A check Apicurio runs before accepting a new version (e.g. FORWARD: new fields are allowed, removed fields are not) |

A schema is identified by the triple **groupId : artifactId : version**, or just its
**globalId** for the fastest path.

### 1.3 What is schema-messaging-core?

`schema-messaging-core` is a **Spring Boot library** (not a runnable application) that
wires Apicurio Registry into RabbitMQ messaging. It is the *plumbing* shared by the
producer service and consumer service in this project.

Its responsibilities in one sentence:
> Validate every outbound message against a live schema before sending it, and validate
> every inbound message before processing it — routing failures intelligently rather than
> silently dropping data.

The library lives in the `schema-messaging-core/` Maven module. Services depend on it:

```
producer-service ──┐
                   ├──► schema-messaging-core ──► Apicurio Registry
consumer-service ──┘                         └──► RabbitMQ topology
```

No domain knowledge lives in this library. It knows nothing about orders or customers.
That knowledge lives in the `order-contracts` and `customer-contracts` modules, which
each contribute a tiny configuration object (a `TypeMapping`) to the core library.

---

## 2. The Data Models

Before explaining any behaviour, you need to know the three immutable data objects that
flow through the library like water through pipes.

### 2.1 SchemaCoordinates — the address of a schema

```java
// groupId:artifactId:version — identifies exactly one schema in the registry
SchemaCoordinates coords = SchemaCoordinates.latest("events.orders", "OrderCreated");
// version is null here, which the library translates to "branch=latest" when talking to the registry API
```

Think of `SchemaCoordinates` like a URL. It tells the library *where* to find the schema,
not what the schema contains.

- `groupId` — the Apicurio group (e.g. `events.orders`)
- `artifactId` — the schema name (e.g. `OrderCreated`)
- `version` — a specific version string, or `null` meaning "latest"

### 2.2 ResolvedSchema — the downloaded schema

Once the library fetches the schema from Apicurio, it wraps the result in a `ResolvedSchema`:

```java
// What the cache holds after a successful registry fetch
record ResolvedSchema(long globalId, SchemaType schemaType, byte[] rawContent, boolean stale) {}
```

- `globalId` — Apicurio's unique integer for this exact version (used as a fast lookup key on the consumer side)
- `schemaType` — the format, currently always `SchemaType.JSON`
- `rawContent` — the raw JSON Schema document bytes (e.g. the contents of `OrderCreated.json`)
- `stale` — `true` when the registry was unreachable and the library is serving a cached copy from last time

### 2.3 TypeMapping — the bridge between Java types and schemas

Each contracts module (e.g. `order-contracts`) contributes one `TypeMapping` bean:

```java
// Defined once in order-contracts, read by schema-messaging-core
record TypeMapping(
    Class<?>          javaType,      // OrderCreated.class
    SchemaCoordinates coordinates,   // events.orders:OrderCreated:null
    SchemaType        schemaType,    // JSON
    String            routingKey     // "orders.created"
) {}
```

The `TypeMappingRegistry` collects all `TypeMapping` beans and provides two lookups:

| Lookup | Used by | How |
|--------|---------|-----|
| By Java type | Producer | `findByJavaType(OrderCreated.class)` → get schema coordinates + routing key |
| By group + artifact | Consumer | `findByGroupAndArtifact("events.orders", "OrderCreated")` → get target Java class |

---

## 3. The Exception Taxonomy

Every exception in the library extends `SchemaMessagingException`. This matters because
the consumer uses the exception *type* to decide whether to retry or send straight to the
dead-letter queue (DLQ).

```
SchemaMessagingException (abstract base)
├── SchemaValidationException       — payload failed JSON Schema validation   (PERMANENT → DLQ)
├── DeserializationException        — bytes could not be parsed to a Java object (PERMANENT → DLQ)
├── SerializationException          — Java object could not be serialized to bytes (PERMANENT → DLQ)
├── IncompatibleSchemaTypeException — message header says "PROTOBUF" but registry says "JSON" (PERMANENT → DLQ)
├── SchemaNotFoundException         — schema doesn't exist in registry yet    (TRANSIENT → retry)
└── RegistryUnavailableException    — registry is down                        (TRANSIENT → retry)
```

**PERMANENT** means: retrying will never fix this. Bad data is bad data. Route to DLQ immediately.

**TRANSIENT** means: the problem is environmental. Wait a bit and try again. The retry ladder
is 5 seconds → 30 seconds → 5 minutes (configured via `events.retry.tier{0,1,2}.ms`).
After 3 retries with no success, the message goes to the DLQ.

---

## 4. Registry Integration

### 4.1 ApicurioClient — the thin HTTP façade

`ApicurioClient` wraps the official Apicurio Java SDK and translates HTTP errors into
library exceptions:

| HTTP response | Library exception |
|---|---|
| 404 Not Found | `SchemaNotFoundException` |
| Network error / timeout | `RegistryUnavailableException` |

It offers three methods:

```java
// Fastest consumer path: registry returns content directly for a globalId
ResolvedSchema fetchByGlobalId(long globalId, SchemaType schemaType)

// Two HTTP calls: metadata (to get globalId + type), then content
ResolvedSchema fetchByCoordinates(SchemaCoordinates coords)

// Convenience: fetchByCoordinates with version=null (latest)
ResolvedSchema latestVersion(String groupId, String artifactId)
```

By default the client talks to `http://localhost:8080` with no authentication. For
production with Keycloak/OIDC you would replace the `ApicurioClient` bean with one
configured with `oauth2(tokenUrl, clientId, clientSecret)`.

### 4.2 SchemaResolver — the caching layer

Calling Apicurio on every message would be slow. `SchemaResolver` sits in front of
`ApicurioClient` and maintains two Caffeine caches:

```
resolveByCoordinates("events.orders:OrderCreated:null")
        │
        ▼
 byCoordinates cache  (TTL=5min, refresh=1min)
        │  miss
        ▼
  ApicurioClient.fetchByCoordinates(...)
        │  also populates ──────────────────────────────►  byGlobalId cache
        ▼
 ResolvedSchema (with globalId)

resolveByGlobalId(42, JSON)   ← fast consumer path, skips coordinate lookup
        │
        ▼
 byGlobalId cache  (simple cache, no auto-load)
        │  miss
        ▼
  ApicurioClient.fetchByGlobalId(42, JSON)
```

**Why two caches?**
The producer knows the coordinates (groupId:artifactId:version). The consumer knows the
`X-Schema-GlobalId` header value. Having a cache per key type means neither path does an
extra lookup.

#### Stale-on-failure strategy

The most important resilience feature: if the registry goes down *after* the schema was
cached, the library serves the old cached value marked `stale=true` and logs a warning.
Only if the schema was *never* cached when the registry goes down does it throw
`RegistryUnavailableException`. This is intentional: a running system should keep
running even if the registry has a blip.

```
Registry up                          → cached as normal (stale=false)
Registry down, schema in cache       → return cached copy (stale=true) + WARN log
Registry down, schema never cached   → throw RegistryUnavailableException (TRANSIENT → retry)
```

#### Pre-warming on startup

`CachePreWarmer` fires after the Spring context is fully started
(`ApplicationReadyEvent`) and calls `schemaResolver.preWarm(coords)` for every
registered `TypeMapping`. This means the first real message doesn't pay a cold-cache
penalty. Pre-warm failures are only warnings — the service still starts.

#### Fail-fast startup validation

When `apicurio.auto-register=OFF` (production mode), `StartupSchemaValidator` runs
*before* the application is ready and throws `IllegalStateException` if any registered
schema is missing from Apicurio. The service refuses to start rather than failing on the
first message at runtime.

---

## 5. Serialization Strategies

`SerializationStrategy` is an interface (a plugin point) with one built-in implementation:
`JsonSchemaStrategy`.

```java
interface SerializationStrategy {
    SchemaType schemaType();
    byte[]  serialize(Object payload, ResolvedSchema schema);    // validate then encode
    Object  deserialize(byte[] bytes, Class<?> targetType, ResolvedSchema schema); // decode (and optionally validate)
}
```

### JsonSchemaStrategy

Uses the [networknt json-schema-validator](https://github.com/networknt/json-schema-validator)
(JSON Schema Draft 2020-12) and Jackson for encoding/decoding.

**Serialize path:**
1. Jackson serializes the Java object to a JSON byte array
2. The raw schema bytes (`ResolvedSchema.rawContent`) are compiled into a `JsonSchema`
   object (cached by `globalId` — max 200 entries, thread-safe)
3. The JSON byte array is validated against the compiled schema
4. If any validation errors occur: throw `SchemaValidationException` with up to 5
   error messages. The bytes are discarded — **nothing is sent to the broker**
5. If valid: return the bytes

**Deserialize path:**
1. Optionally validate the incoming bytes (controlled by `validateOnDeserialize`, default `true`)
2. Jackson deserializes bytes to the target Java type
3. Throw `DeserializationException` if Jackson fails

---

## 6. SchemaAwareMessageConverter — Where Everything Connects

`SchemaAwareMessageConverter` implements Spring AMQP's `MessageConverter` interface.
It is the central piece that turns Java objects into AMQP messages and back.

### Producer path (toMessage)

```
EventPublisher.publish(exchange, orderCreated)
      │
      ▼
1. TypeMappingRegistry.findByJavaType(OrderCreated.class)
   → mapping = {coords: events.orders:OrderCreated:null, routingKey: "orders.created"}

2. SchemaResolver.resolveByCoordinates(mapping.coordinates())
   → schema = {globalId: 42, rawContent: <json schema bytes>}

3. JsonSchemaStrategy.serialize(orderCreated, schema)
   → validate payload against schema (throws if invalid — message never created)
   → Jackson: serialize to bytes

4. Build AMQP Message:
   body = raw JSON bytes  (no envelope wrapper)
   headers:
     X-Schema-GlobalId:    42
     X-Schema-GroupId:     events.orders
     X-Schema-ArtifactId:  OrderCreated
     X-Schema-Version:     1
     X-Schema-Type:        JSON
     X-Message-Id:         <random UUID>
     X-Correlation-Id:     <UUID>
     content-type:         application/json

5. RabbitTemplate.send("events.exchange", "orders.created", message)
```

### Consumer path (fromMessage)

```
RabbitMQ delivers message to @RabbitListener
      │
      ▼
1. Read headers: X-Schema-GroupId=events.orders, X-Schema-ArtifactId=OrderCreated,
   X-Schema-Type=JSON

2. TypeMappingRegistry.findByGroupAndArtifact("events.orders", "OrderCreated")
   → mapping (contains OrderCreated.class)

3. Type validation: X-Schema-Type header ("JSON") must match mapping.schemaType() ("JSON")
   Mismatch → IncompatibleSchemaTypeException  (PERMANENT → DLQ, no retry)

4. Schema resolution — fast path:
   if X-Schema-GlobalId header present:
     SchemaResolver.resolveByGlobalId(42, JSON)  ← no coordinate lookup needed
   else:
     SchemaResolver.resolveByCoordinates(mapping.coordinates())

5. JsonSchemaStrategy.deserialize(messageBody, OrderCreated.class, schema)
   → validates bytes against schema (if validateOnDeserialize=true)
   → Jackson: deserialize to OrderCreated

6. Return OrderCreated object to @RabbitListener handler
```

### Wire format

The AMQP message body is **pure JSON bytes** — nothing more. No Apicurio binary envelope,
no magic bytes prefix. All schema identity travels in the AMQP headers (`X-Schema-*`).
This makes messages easy to inspect with any JSON tool.

---

## 7. Failure Handling

### 7.1 The retry / DLQ topology

The consumer auto-configuration declares this AMQP topology on startup:

```
Publisher ──► events.exchange ──────────────────► orders.queue
                                                       │
                                              exception occurs
                                                       │
                                          ┌────────────┘
                                          ▼
                               EventConsumerSupport.classify(exception)
                                          │
                          ┌───────────────┴───────────────┐
                    TRANSIENT                         PERMANENT
                 (retry eligible)                 (bad data, give up)
                          │                               │
                          ▼                               ▼
              events.retry.exchange              events.dlx exchange
           (TTL queue: 5s/30s/5m)              (events.dlq queue)
                          │
              (after TTL expires)
                          │
                          ▼
                  back to orders.queue
              (up to 3 retries, then DLQ)
```

`DlxRoutingAdvice` is an AOP interceptor wired into the
`SimpleRabbitListenerContainerFactory`. It catches every exception from a
`@RabbitListener` handler, calls `DlxMessageRecoverer.recover(message, ex)`, and ACKs
the original message so RabbitMQ doesn't see a NACK (which would requeue it endlessly).

### 7.2 DLQ headers

When a message lands in the DLQ it carries a full forensic trail:

| Header | Contents |
|--------|---------|
| `X-Failure-Reason` | Exception class name |
| `X-Failure-Message` | Exception message |
| `X-Failure-StackTrace` | Stack trace (truncated to 4 KB) |
| `X-Failure-Original-Routing-Key` | Where the message was heading |
| `X-Failure-Failed-At` | ISO-8601 timestamp |
| `X-Failure-Retry-Count` | How many retries were attempted |

### 7.3 Idempotency filter

`IdempotencyFilter` checks the `X-Message-Id` header before processing. If the same
message ID has been seen within the last hour (Caffeine cache, ~10,000 entries), it is
silently dropped. This is a POC-only safeguard — a production system would use a shared
database, not in-memory state.

---

## 8. Health Checks

`RegistryHealthIndicator` is exposed at `/actuator/health` when Spring Boot Actuator is
on the classpath:

```json
{
  "status": "UP",
  "components": {
    "registry": {
      "status": "UP",
      "details": {
        "preWarmCompleted": true,
        "preWarmErrors": 0
      }
    }
  }
}
```

It probes the registry by requesting a non-existent schema (`__health__/__probe__`).
A 404 response means the registry is alive (it understood the request). A connection
failure means it is DOWN.

---

## 9. Auto-Configuration Summary

The library registers all beans automatically via Spring Boot's `@AutoConfiguration`.
A service only needs to:

1. Add `schema-messaging-core` as a Maven dependency.
2. Set `apicurio.registry.url` in `application.yml`.
3. Contribute `TypeMapping` beans (one per message type, typically from a contracts module).

Every other bean is configured with `@ConditionalOnMissingBean`, meaning a service can
override any default by declaring its own bean of the same type.

| Bean | Purpose | Override to… |
|------|---------|-------------|
| `ApicurioClient` | HTTP façade to registry | Add OIDC/Keycloak auth |
| `SchemaResolver` | Caching layer | Tune TTL, cache size |
| `ObjectMapper` | Jackson instance | Customize date handling, modules |
| `JsonSchemaStrategy` | JSON Schema serde | Disable consumer-side validation |
| `SchemaAwareMessageConverter` | Spring AMQP converter | Custom header logic |
| `EventPublisher` | Publish helper | Add tracing spans |
| `SimpleRabbitListenerContainerFactory` | Consumer container | Custom prefetch count, concurrency |

---

## 10. Putting It All Together — Reading the Code

Now that you know every component, here is how to navigate the source:

```
schema-messaging-core/src/main/java/com/example/messaging/core/
├── model/
│   ├── SchemaCoordinates.java       ← schema address (group:artifact:version)
│   ├── ResolvedSchema.java          ← fetched schema + globalId + stale flag
│   └── SchemaType.java              ← wire format enum (JSON)
├── exception/
│   ├── SchemaMessagingException     ← base; all exceptions carry a context string
│   ├── SchemaValidationException    ← PERMANENT: bad payload
│   ├── SchemaNotFoundException      ← TRANSIENT: schema not registered yet
│   └── RegistryUnavailableException ← TRANSIENT: registry is down
├── registry/
│   ├── ApicurioClient.java          ← HTTP calls to Apicurio SDK
│   ├── SchemaResolver.java          ← Caffeine caches (byCoordinates + byGlobalId)
│   ├── CachePreWarmer.java          ← warms cache on ApplicationReadyEvent
│   └── StartupSchemaValidator.java  ← fail-fast if schema missing (auto-register=OFF)
├── mapping/
│   ├── TypeMapping.java             ← Java type ↔ schema ↔ routing key
│   └── TypeMappingRegistry.java     ← collects all TypeMapping beans, O(1) lookups
├── serde/
│   ├── SerializationStrategy.java   ← interface (plugin point for future formats)
│   └── JsonSchemaStrategy.java      ← JSON Schema: validate + Jackson encode/decode
├── converter/
│   ├── SchemaMessageHeaders.java    ← X-Schema-* header constants + read/write helpers
│   └── SchemaAwareMessageConverter  ← Spring AMQP MessageConverter (the core piece)
├── publisher/
│   └── EventPublisher.java          ← wraps RabbitTemplate; adds routing key lookup
├── consumer/
│   ├── RoutingDecision.java         ← RETRY vs DLQ_DIRECT enum
│   ├── IdempotencyFilter.java       ← dedup on X-Message-Id (POC: in-memory)
│   ├── EventConsumerSupport.java    ← classify(exception) → RoutingDecision
│   ├── DlxMessageRecoverer.java     ← routes to retry or DLQ based on decision
│   └── DlxRoutingAdvice.java        ← AOP interceptor for @RabbitListener containers
└── config/
    ├── SchemaMessagingAutoConfiguration.java          ← core beans
    └── SchemaMessagingConsumerAutoConfiguration.java  ← consumer AMQP topology beans
```

**Suggested reading order for a newcomer:**
1. `model/` — understand the three data objects
2. `exception/` — understand the taxonomy
3. `registry/ApicurioClient` → `SchemaResolver` — how schemas are fetched and cached
4. `mapping/TypeMapping` → `TypeMappingRegistry` — how Java types map to schemas
5. `serde/JsonSchemaStrategy` — validation and encoding
6. `converter/SchemaAwareMessageConverter` — see how everything connects at the message level
7. `consumer/EventConsumerSupport` → `DlxMessageRecoverer` — failure routing
8. `config/` — how all beans are wired automatically
