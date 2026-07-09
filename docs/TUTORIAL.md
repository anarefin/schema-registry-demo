# Tutorial: How This Codebase Works

This is an onboarding tutorial for a new engineer joining this repo. It assumes you
already know Java, Spring Boot, Maven, and general messaging concepts — it does not
re-teach those. What it teaches is *this codebase*: how the modules fit together, and
how one event actually travels from an HTTP request to a consumer, file by file.

| Doc | Answers |
|-----|---------|
| **`docs/TUTORIAL.md`** (this file) | How is the codebase organized, and how does one event flow through it? |
| [`README.md`](../README.md) | How do I build and run it in 15 minutes? |
| [`CONTEXT.md`](../CONTEXT.md) | What do these domain terms mean? |
| [`docs/TESTING-GUIDE.md`](TESTING-GUIDE.md) | How do I manually exercise every scenario end to end? |
| [`docs/adr/0001-code-first-schema-generation.md`](adr/0001-code-first-schema-generation.md), [`spec/code-first-schema.md`](../spec/code-first-schema.md) | Why is the Java record the source of truth instead of the schema? |
| [`docs/adr/0002-contract-owned-amqp-topology.md`](adr/0002-contract-owned-amqp-topology.md), [`spec/contract-owned-amqp-topology.md`](../spec/contract-owned-amqp-topology.md) | Why does each `*-contracts` module own its own AMQP topology? |

Read this document once, in order. Section 2 gives you the map; section 4 walks one
event through every file it touches; section 5 tells you the other five events are the
same walk with different names.

## 1. Orientation

This repo demonstrates **schema-governed messaging**: a producer and a consumer that
share **no compile-time dependency** on each other, only a runtime contract enforced by
a schema registry (Apicurio) and a shared message converter. Neither service imports the
other's code. What keeps them compatible is:

1. Both depend on the same `*-contracts` module (e.g. `order-contracts`), which is the
   single source of truth for an event's shape (a Java **record**) and its generated
   JSON Schema.
2. Every message is validated against that schema on the way out (producer) and,
   depending on config, on the way back in (consumer) — a `SchemaAwareMessageConverter`
   in `schema-messaging-core` does this for every event, not per-event glue code.
3. Schema evolution is gated in CI: an incompatible change to a record fails the build
   before it ever reaches the registry.

The rest of this doc: section 2 is the module map (what depends on what, and why some
dependencies are actually forbidden). Section 4 is the main event — a single event
(`OrderCreated`) traced through every file it touches, from the controller to the
consumer's `@RabbitListener`, including what happens when something goes wrong.

## 2. Module Map

The parent POM aggregates 7 submodules:

| Module | Type | Depends on | Forbidden from depending on | Runtime or build-only |
|---|---|---|---|---|
| `schema-messaging-core` | domain-agnostic library | Spring AMQP, Jackson, Caffeine, Apicurio SDK | `*-contracts`, `amqp-topology-kit` (enforced by `maven-enforcer-plugin`) | runtime |
| `amqp-topology-kit` | domain-agnostic library (leaf) | Spring AMQP only | `schema-messaging-core`, `*-contracts` | runtime |
| `schema-gen-tools` | build-only schema generator (victools) | `*-contracts` | — | **build-only**, never on a service classpath |
| `order-contracts` | contract module | Jackson, jakarta.validation, `spring-rabbit`, `spring-boot-autoconfigure`, `amqp-topology-kit` | — | runtime |
| `customer-contracts` | contract module (mirror of `order-contracts`) | same as above | — | runtime |
| `producer-service` | Spring Boot app | `schema-messaging-core`, `order-contracts`, `customer-contracts` | — | runtime |
| `consumer-service` | Spring Boot app | `schema-messaging-core`, `order-contracts`, `customer-contracts` | — | runtime |

```mermaid
graph LR
    core["schema-messaging-core<br/>(converter, resolver, publisher, DLX routing)"]
    kit["amqp-topology-kit<br/>(naming + retry-ladder factory, leaf)"]
    oc["order-contracts<br/>(records + schemas + topology autoconfig)"]
    cc["customer-contracts<br/>(records + schemas + topology autoconfig)"]
    gen["schema-gen-tools<br/>(build-only, victools)"]
    prod["producer-service"]
    cons["consumer-service"]

    oc --> kit
    cc --> kit
    prod --> core
    prod --> oc
    prod --> cc
    cons --> core
    cons --> oc
    cons --> cc
    gen -.->|build-time only, generates schemas into| oc
    gen -.->|build-time only, generates schemas into| cc

    core -.->|"✗ banned by enforcer plugin"| oc
    core -.->|"✗ banned by enforcer plugin"| kit

    style gen stroke-dasharray: 5 5
    style kit stroke-width:2px
```

### Core libraries

`schema-messaging-core` holds everything reusable and domain-agnostic: the
`SchemaAwareMessageConverter`, `SchemaResolver` (Apicurio caching), `EventPublisher`,
and the consumer-side DLX/retry machinery (`EventConsumerSupport`, `DlxMessageRecoverer`,
`DlxRoutingAdvice`). It knows nothing about orders or customers.

`amqp-topology-kit` is a pure leaf module: `EventTopologyFactory` (builds the
queue/DLQ/retry-ladder `Declarable`s for one routing key) and `TopologyNaming` (the
naming convention: `<routingKey>.queue`, `<routingKey>.dlq`, retry tier suffixes
`5s`/`30s`/`5m`). It's a library the contracts modules call, not a Spring auto-config
itself.

### Build tooling

`schema-gen-tools` is a Maven-plugin-invoked generator (victools) that reads the
contract modules' Java records and writes their `*.schema.json` files at
`process-classes` time. It is never a runtime dependency of any service — see
[ADR-0001](adr/0001-code-first-schema-generation.md) for why the record, not the schema,
is authored by hand.

### Contracts

`order-contracts` and `customer-contracts` each own three things for their domain: the
event records, the generated JSON Schemas, and — per
[ADR-0002](adr/0002-contract-owned-amqp-topology.md) — a
`@AutoConfiguration` class (`OrderTopologyAutoConfiguration` /
`CustomerTopologyAutoConfiguration`) that declares that domain's exchanges, queues,
DLQs, and retry ladder. Nothing about AMQP topology lives in the services themselves.

### Services

`producer-service` and `consumer-service` are thin: a `*ContractsConfiguration` class
per service registering one `TypeMapping` bean per event, plus controllers (producer) or
`@RabbitListener` methods (consumer). Topology and converter wiring arrive automatically
via Spring Boot auto-configuration — there is no per-service topology or converter glue
to maintain.

**Wire format** (see [`spec/code-first-schema.md`](../spec/code-first-schema.md) §6 for
the full rationale): the AMQP message body is the raw serialized JSON bytes only, no
envelope. Schema identity travels entirely in `X-Schema-*` headers plus
`X-Correlation-Id`.

**Failure model** (see [ADR-0002](adr/0002-contract-owned-amqp-topology.md) and
[`spec/contract-owned-amqp-topology.md`](../spec/contract-owned-amqp-topology.md)):
transient failures retry through a 5s/30s/5m TTL ladder (max 3 tries) before landing on
the DLQ; permanent failures (validation, deserialization, type mismatch) go straight to
the DLQ. Section 4.8 shows exactly which code makes that decision.

## 3. Vocabulary You'll See in the Trace

CONTEXT.md's glossary covers business terms (Order, Customer, the six events). This
table is the code-level vocabulary for section 4 — the classes the walkthrough keeps
naming, so you don't have to keep looking them up mid-trace.

| Class | Module | Role |
|---|---|---|
| `TypeMapping` / `TypeMappingRegistry` | `schema-messaging-core` | Maps a Java type ↔ `SchemaCoordinates` ↔ `SchemaType` ↔ AMQP routing key. One `TypeMapping` bean per event. |
| `SchemaCoordinates` / `ResolvedSchema` | `schema-messaging-core` | `SchemaCoordinates` is the cache key (group/artifact/version); `ResolvedSchema` is the cached value (raw schema bytes + globalId + a `stale` flag). |
| `SchemaAwareMessageConverter` | `schema-messaging-core` | The one Spring AMQP `MessageConverter` that does `toMessage`/`fromMessage` for every event — the single validation authority. |
| `SchemaResolver` | `schema-messaging-core` | Caffeine cache in front of `ApicurioClient`; serves stale-on-registry-outage. |
| `JsonSchemaStrategy` | `schema-messaging-core` | The `SerializationStrategy` implementation: validates (networknt, Draft-07) then (de)serializes with Jackson. |
| `EventPublisher` | `schema-messaging-core` | Producer-side wrapper: `TypeMapping` lookup → `messageConverter.toMessage()` → `rabbitTemplate.send()`. |
| `DlxRoutingAdvice` | `schema-messaging-core` | AOP advice wrapped around every listener invocation; catches exceptions and hands them to the recoverer. |
| `DlxMessageRecoverer` | `schema-messaging-core` | Decides DLQ vs. retry-exchange and actually sends the message there. |
| `EventConsumerSupport` | `schema-messaging-core` | `classify(Exception)` — the permanent-vs-transient decision table — and DLQ failure-header population. |
| `EventTopologyFactory` / `TopologyNaming` | `amqp-topology-kit` | Builds the queue/DLQ/retry-tier `Declarable`s for one routing key, and supplies the naming convention. |
| `OrderTopologyAutoConfiguration` | `order-contracts` | Declares `events.orders.*` exchanges and calls the factory once per order routing key. |

## 4. Deep Dive: `OrderCreated` End-to-End

We'll trace one event, `OrderCreated`, from an HTTP POST all the way to the consumer's
`@RabbitListener`, including the failure path. Every other event in this system
(`OrderShipped`, `OrderCancelled`, and the three customer events) takes an identical
code path — see section 5.

Roadmap (each numbered step below is one hop):

1. HTTP request → `OrderController` builds the record and hands it to `EventPublisher`.
2. `EventPublisher.publish()` looks up the `TypeMapping`, calls the converter, sends via `RabbitTemplate`.
3. The `TypeMapping` bean itself, registered in `producer-service`.
4. The converter's produce path (`toMessage`): resolve schema → validate → serialize → stamp headers.
5. The AMQP topology that the message lands in — declared once at startup, not per-publish.
6. The consumer's listener container + `@RabbitListener`.
7. The converter's consume path (`fromMessage`): read headers → resolve schema → validate → deserialize.
8. What happens when any of the above throws — the DLX/retry routing decision.

### 4.1 HTTP entrypoint

`producer-service/src/main/java/com/example/producer/controller/OrderController.java:48-57`:

```java
public void createOrder(@RequestBody CreateOrderRequest request) {
    ...
    eventPublisher.publish(OrderEventRouting.EXCHANGE, event);
}
```

The controller builds the `OrderCreated` record from the request body and delegates
everything else — validation, serialization, header population, sending — to
`eventPublisher.publish()`. There's no manual field-checking here; validation happens
against the schema, not in the controller.

### 4.2 Publish

`schema-messaging-core/src/main/java/com/example/messaging/core/publisher/EventPublisher.java:42-53`:

```java
public void publish(String exchange, Object event) {
    TypeMapping mapping = typeMappingRegistry.findByJavaType(event.getClass())
            .orElseThrow(...);

    MessageProperties props = new MessageProperties();
    Message message = messageConverter.toMessage(event, props);

    rabbitTemplate.send(exchange, mapping.routingKey(), message);
}
```

Three steps: look up the `TypeMapping` for this Java type, run it through the converter
(section 4.4 — this is where validation happens), send it to the exchange using the
routing key from the `TypeMapping`.

### 4.3 Where the `TypeMapping` bean comes from

`producer-service/src/main/java/com/example/producer/config/OrderContractsConfiguration.java:31-35`:

```java
@Bean
public TypeMapping orderCreatedMapping() {
    return new TypeMapping(OrderCreated.class, coords("OrderCreated"),
            SchemaType.JSON, OrderEventRouting.CREATED_ROUTING_KEY);
}
```

One `@Bean` method per event, all in one `@Configuration` class per service. The
`coords()` helper (lines 25-29 of the same file) resolves to
`SchemaCoordinates.latest(...)` unless `schema.orders.pinned-version` is set in
`application.yml`, in which case every artifact is locked to that version — this is the
version-pinning mechanism `docs/TESTING-GUIDE.md` §12-13 demonstrates.

### 4.4 Convert: produce path

`schema-messaging-core/.../converter/SchemaAwareMessageConverter.java:66-93` (`toMessage`):

```java
public Message toMessage(Object object, MessageProperties messageProperties) {
    TypeMapping mapping = typeMappingRegistry.findByJavaType(type).orElseThrow(...);
    ResolvedSchema schema = schemaResolver.resolveByCoordinates(mapping.coordinates());
    SerializationStrategy strategy = strategyFor(mapping.schemaType());
    byte[] bytes = strategy.serialize(object, schema);
    SchemaMessageHeaders.setSchemaHeaders(messageProperties, schema.globalId(), ...);
    return new Message(bytes, messageProperties);
}
```

- `schemaResolver.resolveByCoordinates()` (`registry/SchemaResolver.java:78-81`) hits a
  Caffeine `LoadingCache`; on a cache miss it fetches from Apicurio, and if Apicurio is
  down it serves the last-known-good value with a WARN rather than failing the publish.
- `strategy.serialize()` → `JsonSchemaStrategy.serialize()` (`serde/JsonSchemaStrategy.java:66-77`)
  Jackson-serializes the record, then calls `validate()` (lines 98-119): compiles/caches
  the schema (networknt, Draft-07) and validates the JSON against it. **If validation
  fails, `SchemaValidationException` is thrown and no message is ever sent** — the
  producer sees a 400, not a message on the broker.
- `SchemaMessageHeaders.setSchemaHeaders()` stamps the identity headers:

  | Header | Purpose |
  |---|---|
  | `X-Schema-GlobalId` | Apicurio global content ID — lets the consumer skip a coordinate lookup |
  | `X-Schema-GroupId` / `X-Schema-ArtifactId` / `X-Schema-Version` | Full coordinates (fallback path) |
  | `X-Schema-Type` | `JSON` today — the SPI exists for future formats (e.g. Avro) |
  | `X-Correlation-Id` | Generated if not already present |

```mermaid
sequenceDiagram
    participant C as OrderController
    participant P as EventPublisher
    participant TM as TypeMappingRegistry
    participant SR as SchemaResolver
    participant AR as Apicurio Registry
    participant JS as JsonSchemaStrategy
    participant RT as RabbitTemplate / Broker

    C->>P: publish(exchange, OrderCreated)
    P->>TM: findByJavaType(OrderCreated.class)
    TM-->>P: TypeMapping
    P->>JS: toMessage(event) [via converter]
    JS->>SR: resolveByCoordinates(coords)
    SR->>AR: fetch (cache miss only)
    AR-->>SR: schema content + globalId
    SR-->>JS: ResolvedSchema
    JS->>JS: serialize() + validate()
    alt validation fails
        JS-->>P: throws SchemaValidationException
        Note over P: no message sent, publish call fails
    else validation passes
        JS-->>P: bytes + X-Schema-* headers
        P->>RT: send(exchange, routingKey, message)
    end
```

### 4.5 Topology: how the queues got there

This didn't happen at publish time — it happened once, at consumer startup.
`order-contracts/.../topology/OrderTopologyAutoConfiguration.java:31-67` declares three
`TopicExchange` beans (`events.orders.exchange`, `.dlx`, `.retry.exchange`, lines 31-47),
then for each of the three order routing keys calls
`EventTopologyFactory.declarablesForEvent()` (line 63):

```java
for (String routingKey : List.of(
        OrderEventRouting.CREATED_ROUTING_KEY,
        OrderEventRouting.SHIPPED_ROUTING_KEY,
        OrderEventRouting.CANCELLED_ROUTING_KEY)) {
    declarables.addAll(EventTopologyFactory.declarablesForEvent(
            routingKey, ordersExchange, ordersDlx, ordersRetryExchange, tierTtls));
}
```

`EventTopologyFactory.declarablesForEvent()` (`amqp-topology-kit/.../EventTopologyFactory.java:36-59`)
builds, per routing key: the main queue + binding (`TopologyNaming.queueName`, e.g.
`orders.created.queue`), the DLQ + binding (`TopologyNaming.dlqName`, e.g.
`orders.created.dlq`), and three TTL retry queues + bindings (5s/30s/5m,
`TopologyNaming.retryRoutingKey`). This is `@AutoConfiguration` — no service writes any
topology code itself. The declared beans are picked up and idempotently applied to the
broker on startup by the shared `RabbitAdmin` bean
(`SchemaMessagingConsumerAutoConfiguration.java:42-46`).

Adding a fourth order event means adding one routing key to that `List.of(...)` — the
queue/DLQ/retry-ladder for it is generated automatically.

### 4.6 Consumer wiring

`SchemaMessagingConsumerAutoConfiguration.java:63-75` builds the
`rabbitListenerContainerFactory` bean, wiring in the *same*
`SchemaAwareMessageConverter` used on the producer side, plus a `DlxRoutingAdvice`
advice chain (section 4.8).

`consumer-service/src/main/java/com/example/consumer/listener/OrderEventListener.java:23-28`:

```java
@RabbitListener(queues = OrderEventRouting.CREATED_QUEUE,
                containerFactory = "rabbitListenerContainerFactory")
public void onOrderCreated(OrderCreated event) {
    log.info("Received OrderCreated orderId={} ...", event.orderId(), ...);
}
```

Spring AMQP calls `fromMessage()` on the raw bytes *before* this method body ever runs —
by the time `onOrderCreated` executes, `event` is already a validated, typed
`OrderCreated` record.

### 4.7 Convert: consume path

`SchemaAwareMessageConverter.fromMessage()` (lines 98-134):

```java
public Object fromMessage(Message message) {
    String groupId = SchemaMessageHeaders.getGroupId(props);
    String artifactId = SchemaMessageHeaders.getArtifactId(props);
    String headerTypeName = SchemaMessageHeaders.getSchemaTypeName(props);

    TypeMapping mapping = typeMappingRegistry.findByGroupAndArtifact(groupId, artifactId)
            .orElseThrow(...);

    if (headerTypeName != null && !headerTypeName.equalsIgnoreCase(mapping.schemaType().name())) {
        throw new IncompatibleSchemaTypeException(...);
    }

    ResolvedSchema schema = resolveSchema(props, mapping.schemaType());
    return strategyFor(mapping.schemaType()).deserialize(message.getBody(), mapping.javaType(), schema);
}
```

Notable details:

- **Type-mismatch guard** (lines 113-117): the raw `X-Schema-Type` header string is
  compared against what this consumer's `TypeMapping` expects. If a stale producer sent
  a different schema type (e.g. `PROTOBUF`), this throws `IncompatibleSchemaTypeException`
  immediately, rather than letting a mismatched deserializer fail confusingly downstream.
  This is a **permanent** failure (section 4.8).
- **`resolveSchema()`** (lines 138-151) prefers the `X-Schema-GlobalId` fast path
  (`resolveByGlobalId`, skips a coordinate lookup entirely) and only falls back to
  group/artifact/version coordinates if no globalId header is present.
- `JsonSchemaStrategy.deserialize()` (lines 80-94) validates (if
  `validateOnDeserialize` is enabled — the default) then Jackson-deserializes.
  Deserialization failures are wrapped as `DeserializationException`.

```mermaid
sequenceDiagram
    participant B as Broker
    participant C as SchemaAwareMessageConverter
    participant TM as TypeMappingRegistry
    participant SR as SchemaResolver
    participant JS as JsonSchemaStrategy
    participant L as OrderEventListener

    B->>C: deliver message (bytes + X-Schema-* headers)
    C->>TM: findByGroupAndArtifact(groupId, artifactId)
    TM-->>C: TypeMapping
    C->>C: compare X-Schema-Type vs mapping.schemaType()
    C->>SR: resolveByGlobalId(globalId) [fast path]
    SR-->>C: ResolvedSchema
    C->>JS: deserialize(bytes, OrderCreated.class, schema)
    JS-->>C: validated OrderCreated record
    C->>L: onOrderCreated(event)
```

### 4.8 What happens when it fails

If anything above throws — schema-not-found, validation failure, deserialization
failure, registry unavailable — `DlxRoutingAdvice.invoke()`
(`consumer/DlxRoutingAdvice.java:29-54`) is wrapped around the entire listener
invocation and catches it:

```java
try {
    return invocation.proceed();
} catch (Throwable t) {
    ...
    recoverer.recover(message, ex);
    return null; // suppress original exception → container ACKs the message
}
```

It delegates to `DlxMessageRecoverer.recover()` (`consumer/DlxMessageRecoverer.java:44-69`),
which asks `EventConsumerSupport.classify()` (`consumer/EventConsumerSupport.java:52-61`)
for a routing decision:

| Exception | Classification |
|---|---|
| `SchemaValidationException`, `DeserializationException`, `SerializationException`, `IncompatibleSchemaTypeException` | **PERMANENT** → `DLQ_DIRECT`, no retry |
| Everything else (e.g. `RegistryUnavailableException`, `SchemaNotFoundException`) | **TRANSIENT** → `RETRY` |

```mermaid
flowchart TD
    A[Listener invocation throws] --> B["EventConsumerSupport.classify(exception)"]
    B -->|permanent: validation, deserialization,<br/>serialization, type-mismatch| C[DLQ_DIRECT]
    B -->|transient: everything else,<br/>e.g. registry unavailable| D{"retryCount >= maxRetries (3)?"}
    D -->|yes| C
    D -->|no| E["bump X-Retry-Count,<br/>send to *.retry.exchange<br/>with tier routing key (5s/30s/5m)"]
    C --> F["populateFailureHeaders()<br/>send to *.dlx exchange"]
```

`DlxMessageRecoverer.recover()` derives the domain's DLX/retry exchange names from the
message's *actual received exchange* (`props.getReceivedExchange()`, regex-stripping
`.exchange` → `.dlx` / `.retry.exchange`) rather than a hardcoded name — this is why the
one shared `DlxMessageRecoverer` bean in `schema-messaging-core` needs no dependency on
any `*-contracts` module (see [ADR-0002](adr/0002-contract-owned-amqp-topology.md)).

**Go run it yourself**: section 4 traced the code; to watch this happen against a live
broker, run [`docs/TESTING-GUIDE.md`](TESTING-GUIDE.md) §8 (happy path), §9 (validation
failure), §10 (DLQ), and §11 (retry ladder).

## 5. The Other Five Events

`OrderShipped`, `OrderCancelled`, and the three customer events
(`CustomerRegistered`, `CustomerAddressAdded`, `CustomerTierChanged`) all go through
*exactly* the same classes traced in section 4. Only the record shape, routing key, and
exchange group differ:

| Event | Module | Routing key | Exchange group |
|---|---|---|---|
| `OrderCreated` | `order-contracts` | `orders.created` | `events.orders.exchange` |
| `OrderShipped` | `order-contracts` | `orders.shipped` | `events.orders.exchange` |
| `OrderCancelled` | `order-contracts` | `orders.cancelled` | `events.orders.exchange` |
| `CustomerRegistered` | `customer-contracts` | `customers.registered` | `events.customers.exchange` |
| `CustomerAddressAdded` | `customer-contracts` | `customers.address-added` | `events.customers.exchange` |
| `CustomerTierChanged` | `customer-contracts` | `customers.tier-changed` | `events.customers.exchange` |

`CustomerTopologyAutoConfiguration` is the exact mirror of
`OrderTopologyAutoConfiguration` (section 4.5) for the customer domain. If you
understand section 4, you understand all six events — the only new code you'd write
for a seventh event is a new record, a routing-key constant, one `TypeMapping` bean, one
line in the topology auto-configuration's routing-key list, and one `@RabbitListener`
method.

## 6. Where to Go Next

| Next step | Doc |
|---|---|
| Actually run the system | [`README.md`](../README.md) |
| Exercise every scenario manually (happy path, validation failure, DLQ, retry, pinning, evolution) | [`docs/TESTING-GUIDE.md`](TESTING-GUIDE.md) |
| Look up a domain term | [`CONTEXT.md`](../CONTEXT.md) |
| Understand why code-first schema generation was chosen | [ADR-0001](adr/0001-code-first-schema-generation.md), [`spec/code-first-schema.md`](../spec/code-first-schema.md) |
| Understand why contracts modules own their own AMQP topology | [ADR-0002](adr/0002-contract-owned-amqp-topology.md), [`spec/contract-owned-amqp-topology.md`](../spec/contract-owned-amqp-topology.md) |
