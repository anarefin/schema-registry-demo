# Specification: Simplified Publish/Listen — Internal Routing Resolution

## Purpose

Let a developer write only the event, on both sides of the wire:

- **Publish:** `eventPublisher.publish(event)` — no exchange argument. Today's
  `publish(String exchange, Object event)` still makes the caller know and pass
  `OrderEventRouting.EXCHANGE`/`CustomerEventRouting.EXCHANGE` even though `EventPublisher` already
  resolves the routing key from the event's `TypeMapping`.
- **Listen:** a bare `void onCustomerRegistered(CustomerRegistered event)` — no queue name, no
  container factory bean name. Today's `@RabbitListener(queues = CustomerEventRouting.REGISTERED_QUEUE,
  containerFactory = "rabbitListenerContainerFactory")` makes the caller spell out the queue
  constant Spring's annotation model requires as a compile-time/SpEL value.

This **amends Guiding Principle 3 of `spec/contract-owned-amqp-topology.md`**, which states "the
hand-written `TypeMapping` beans ... stay exactly as they are — a separate concern from physical
AMQP topology." `TypeMapping` is no longer untouched: it now also carries the exchange, closing
the gap between schema-resolution wiring and physical topology for the one field that was still
caller-supplied. `spec/contract-owned-amqp-topology.md` should be read historically for everything
else — topology ownership, retry ladder, DLX/retry exchange derivation are unaffected.

This is a one-shot, clean cutover, consistent with this POC's established style (no live
production traffic, two domains only): the old two-argument `publish` overload is removed rather
than kept alongside the new one, and `@RabbitListener` is fully replaced on both listener classes
rather than partially migrated.

## Guiding Principles

1. **`TypeMapping` is the single source of routing metadata for an event** — Java type, schema
   coordinates, schema type, routing key, and now exchange. A caller (publisher or listener) never
   supplies routing information the framework can already look up from the event's own type.
2. **No back-compat surface.** `EventPublisher.publish(String, Object)` is deleted, not
   deprecated-and-kept. `@RabbitListener` is deleted from `OrderEventListener`/`CustomerEventListener`,
   not left running alongside the new mechanism.
3. **Queue names stay a derived, not declared, value.** `TopologyNaming.queueName(routingKey)` is
   already how the topology auto-configuration computes queue names (Guiding Principle 5 of
   `contract-owned-amqp-topology.md`) — the listener-registration mechanism reuses that same
   convention instead of introducing a second one.
4. **DLQ/retry failure routing is unaffected.** It is wired as an advice chain on the shared
   `rabbitListenerContainerFactory` bean (Guiding Principle 6 of `contract-owned-amqp-topology.md`),
   a factory-level property inherited by any endpoint bound to that factory — annotation-driven or
   programmatic. This change must not touch `EventConsumerSupport`, `DlxMessageRecoverer`, or
   `DlxRoutingAdvice`.

---

## Scope

### In scope
- Widen `TypeMapping` (`event-contract-kit`) with a 5th field, `exchange`.
- Populate `exchange` in `OrderTypeMappingAutoConfiguration`/`CustomerTypeMappingAutoConfiguration`
  from the existing `OrderEventRouting.EXCHANGE`/`CustomerEventRouting.EXCHANGE` constants.
- Collapse `EventPublisher.publish(String, Object)` to `publish(Object)`; update all 10 call sites.
- New `@BitsEventHandler` marker annotation + `BitsEventHandlerRegistrar` (`RabbitListenerConfigurer`)
  in `schema-messaging-core`, registering listener endpoints programmatically at startup by
  resolving each annotated method's single parameter type through `TypeMappingRegistry` +
  `TopologyNaming.queueName(routingKey)`.
- Migrate `OrderEventListener`/`CustomerEventListener` from `@RabbitListener` to `@BitsEventHandler`.
- Remove the six now-dead `*_QUEUE` constants from `OrderEventRouting`/`CustomerEventRouting`.
- `EventPublisherTest` (new — none exists today).
- ADR-0007 (TypeMapping carries exchange), ADR-0008 (`@BitsEventHandler` programmatic listener
  registration), `CONTEXT.md` glossary update.

### Out of scope / deferred
- Any change to the retry ladder, DLX/retry exchange derivation, or `DlxMessageRecoverer`/
  `DlxRoutingAdvice`/`EventConsumerSupport` — untouched, per Guiding Principle 4 above.
- Any change to topology auto-configuration (`OrderTopologyAutoConfiguration`/
  `CustomerTopologyAutoConfiguration`) — they already derive queue/DLQ names from routing keys via
  `TopologyNaming`, independent of the `*_QUEUE` constants being removed here.
- `*_DLQ` and `*_ROUTING_KEY` constants — both still have live callers (`DlxRoutingIT`,
  topology/type-mapping autoconfig, the `/api/orders/poison` demo) and are unchanged.
- A coexistence/phased rollout — explicitly rejected in favor of a one-shot cutover, matching
  `contract-owned-amqp-topology.md`'s precedent.

---

## Design

### D1 — `TypeMapping` gains an `exchange` field

`event-contract-kit`'s `TypeMapping` record grows from 4 to 5 fields, appending (not inserting) to
keep existing positional-constructor call sites additive:

```java
public record TypeMapping(
        Class<?> javaType,
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        String routingKey,
        String exchange
) {}
```

Rejected alternatives:
- **Derive exchange from `coordinates.groupId()` via a naming convention** (`groupId + ".exchange"`)
  — relies on an implicit string-suffix convention enforced nowhere; a future exchange name that
  doesn't follow the pattern would silently break.
- **Separate exchange registry**, independent of `TypeMapping` — avoids widening the record but
  introduces a second parallel lookup concept for no benefit, since exchange is exactly the kind
  of per-event routing metadata `TypeMapping` already exists to carry (see routing key).

`OrderTypeMappingAutoConfiguration`/`CustomerTypeMappingAutoConfiguration` populate the field from
the pre-existing `OrderEventRouting.EXCHANGE`/`CustomerEventRouting.EXCHANGE` constants — no new
constants, no new source of truth for the exchange name itself, only a new place it also gets
recorded.

### D2 — `EventPublisher.publish(Object)`

`EventPublisher` (`schema-messaging-core`) already resolves `TypeMapping` via
`TypeMappingRegistry.findByJavaType(event.getClass())` to read `routingKey()`. With D1 in place,
the same lookup also yields `exchange()`:

```java
public void publish(Object event) {
    TypeMapping mapping = typeMappingRegistry.findByJavaType(event.getClass())
            .orElseThrow(() -> new IllegalStateException(
                    "No TypeMapping for " + event.getClass().getName()));
    MessageProperties props = new MessageProperties();
    Message message = messageConverter.toMessage(event, props);
    rabbitTemplate.send(mapping.exchange(), mapping.routingKey(), message);
}
```

The two-argument overload is deleted outright (Guiding Principle 2) — all 10 call sites
(`OrderController`, `CustomerController`, `OrderCreatedIT`, `CustomerRegisteredIT`,
`ProducerValidationTest` mocks) update in the same change. `OrderController.publishPoison()` and
`DlxRoutingIT` call `RabbitTemplate.send(...)` directly today, bypassing `EventPublisher` — both
unaffected.

### D3 — `@BitsEventHandler` + `BitsEventHandlerRegistrar`

Spring's `@RabbitListener(queues = ...)` attribute is resolved once at container bootstrap from a
compile-time constant or SpEL expression — it cannot be inferred from a bare method parameter type
without programmatic registration. `com.example.messaging.core.consumer` (schema-messaging-core)
gains:

- **`BitsEventHandler`** — `@Target(METHOD) @Retention(RUNTIME)`, no attributes. The event type is
  the annotated method's single parameter.
- **`BitsEventHandlerRegistrar implements RabbitListenerConfigurer`** — a `@Bean` in
  `SchemaMessagingConsumerAutoConfiguration`, constructor-injected with `ApplicationContext` +
  `TypeMappingRegistry`. In `configureRabbitListeners(...)`:
  1. Find every method across all singleton beans annotated `@BitsEventHandler`
     (`MethodIntrospector.selectMethods`, the same lookup style Spring's own
     `RabbitListenerAnnotationBeanPostProcessor` uses for `@RabbitListener`).
  2. Validate exactly one parameter; resolve its `TypeMapping` via
     `typeMappingRegistry.findByJavaType(...)`; fail fast (`IllegalStateException`) on either
     violation — a startup-time misconfiguration, not a runtime concern.
  3. Compute `TopologyNaming.queueName(mapping.routingKey())` — reusing the exact convention the
     topology auto-configuration already uses, per Guiding Principle 3.
  4. Build a `MethodRabbitListenerEndpoint` (bean, method, id, queue name) and register it against
     the existing `"rabbitListenerContainerFactory"` bean — the same factory every `@RabbitListener`
     endpoint used, so the advice chain (`DlxRoutingAdvice`) and message converter
     (`SchemaAwareMessageConverter`) are inherited unchanged (Guiding Principle 4).
  5. Explicitly build and set a `DefaultMessageHandlerMethodFactory` on each endpoint. Unlike
     `@RabbitListener`-annotated methods — where `RabbitListenerAnnotationBeanPostProcessor` sets
     the handler-method factory on the endpoints it builds itself — a `MethodRabbitListenerEndpoint`
     registered programmatically via `RabbitListenerEndpointRegistrar.registerEndpoint(...)` is
     never given one automatically (confirmed by reading `RabbitListenerEndpointRegistrar`'s
     `registerAllEndpoints()`, which only resolves the container factory, not a handler-method
     factory); omitting this step throws `NullPointerException` at first message delivery.

This introduces the first `RabbitListenerConfigurer` in the codebase — a new pattern, not an
extension of an existing one.

### D4 — Listener migration + dead-constant removal

`OrderEventListener`/`CustomerEventListener` (`consumer-service`) drop
`@RabbitListener(queues = ..., containerFactory = "rabbitListenerContainerFactory")` in favor of
bare `@BitsEventHandler` on each method. `OrderEventRouting`/`CustomerEventRouting` lose their six
`*_QUEUE` constants (`CREATED_QUEUE`, `SHIPPED_QUEUE`, `CANCELLED_QUEUE`,
`REGISTERED_QUEUE`, `ADDRESS_ADDED_QUEUE`, `TIER_CHANGED_QUEUE`) — confirmed to have no callers
left anywhere in the repo besides the listener methods being migrated. `*_DLQ`/`*_ROUTING_KEY`
constants are untouched (live callers remain: `DlxRoutingIT`, topology/type-mapping autoconfig,
poison demo).

---

## Tasks

### Phase 1 — `TypeMapping` + type-mapping wiring
- [ ] **T-1.1** Add `exchange` field to `TypeMapping` (D1).
- [ ] **T-1.2** Populate it in `OrderTypeMappingAutoConfiguration`/`CustomerTypeMappingAutoConfiguration`.

### Phase 2 — `EventPublisher` + call sites
- [ ] **T-2.1** Collapse `publish` to a single `Object`-only overload (D2).
- [ ] **T-2.2** Update all 10 call sites; remove now-unused `*EventRouting` imports where applicable.
- [ ] **T-2.3** Add `EventPublisherTest`.

### Phase 3 — `@BitsEventHandler` mechanism
- [ ] **T-3.1** `BitsEventHandler` annotation.
- [ ] **T-3.2** `BitsEventHandlerRegistrar` (D3), registered as a `@Bean` in
  `SchemaMessagingConsumerAutoConfiguration`.

### Phase 4 — Listener migration + cleanup
- [ ] **T-4.1** Migrate `OrderEventListener`/`CustomerEventListener` to `@BitsEventHandler` (D4).
- [ ] **T-4.2** Remove the six dead `*_QUEUE` constants; update `*EventRouting` javadoc.

### Phase 5 — Documentation
- [ ] **T-5.1** ADR-0007 (`TypeMapping` carries exchange).
- [ ] **T-5.2** ADR-0008 (`@BitsEventHandler` programmatic listener registration).
- [ ] **T-5.3** Update `CONTEXT.md`'s `TypeMapping` glossary row + mention `@BitsEventHandler`.

---

## Acceptance / Verification

1. `./mvnw -pl event-contract-kit,order-contracts,customer-contracts,schema-messaging-core,producer-service,consumer-service -am compile`
   — all affected modules compile against the widened `TypeMapping` and new annotation.
2. `./mvnw -pl schema-messaging-core test -Dtest=EventPublisherTest` — new unit test passes.
3. `./mvnw -pl producer-service test -Dtest=ProducerValidationTest` — mocked-publish signature
   change doesn't break the validate-then-publish 400 path.
4. `./mvnw -pl consumer-service verify -Dtest=OrderCreatedIT,CustomerRegisteredIT,DlxRoutingIT` —
   Testcontainers round-trip proves `@BitsEventHandler` resolves the right queue and dispatches to
   the right method; `DlxRoutingIT` proves the shared advice chain still applies unchanged.
5. `./mvnw clean install` — full reactor build, run last.
