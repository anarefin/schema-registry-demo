# ADR-0008: @BitsEventHandler Programmatic Listener Registration

## Status

Accepted.

## Context

Listener methods declared their queue explicitly:

```java
@RabbitListener(queues = CustomerEventRouting.REGISTERED_QUEUE,
                containerFactory = "rabbitListenerContainerFactory")
public void onCustomerRegistered(CustomerRegistered event) { ... }
```

The goal (spec `simplified-publish-and-listen.md`) is a bare handler — only the event-typed
parameter, no queue name, no container factory reference. Spring's `@RabbitListener(queues = ...)`
attribute is resolved once at container bootstrap from a compile-time constant or a SpEL
expression; it cannot be inferred from a method's parameter type without programmatic
registration. `OrderEventRouting`'s javadoc said this explicitly: its constants exist as "plain
`String` constants only ... so `@RabbitListener` ... can reference them as compile-time
constants."

Two options were considered:
1. **A new marker annotation + `RabbitListenerConfigurer`** that registers listener endpoints
   programmatically at startup, resolving the queue from the handler's own parameter type.
2. **Keep `@RabbitListener`, shrink the queue argument** to a SpEL expression resolved from a bean
   keyed by event class (e.g. `queues = "#{@eventQueueNames.of(T(...CustomerRegistered))}"`).

Option 2 keeps Spring's declarative model but still requires a per-method expression referencing
the type — it doesn't reach "just the event type," only a shorter way to spell out the same
information.

## Decision

Introduce `@BitsEventHandler` (`schema-messaging-core`, package `com.example.messaging.core.consumer`)
— no attributes, `@Target(METHOD)`:

```java
@BitsEventHandler
public void onCustomerRegistered(CustomerRegistered event) { ... }
```

and `BitsEventHandlerRegistrar implements RabbitListenerConfigurer`, registered as a `@Bean`
alongside `rabbitListenerContainerFactory` in `SchemaMessagingConsumerAutoConfiguration`. In
`configureRabbitListeners(...)` it:

1. Finds every `@BitsEventHandler`-annotated method across all singleton beans
   (`MethodIntrospector.selectMethods`, the same lookup style Spring's own
   `RabbitListenerAnnotationBeanPostProcessor` uses for `@RabbitListener`).
2. Validates exactly one parameter; resolves its `TypeMapping` via `TypeMappingRegistry`; fails
   fast (`IllegalStateException`) on either violation.
3. Derives the queue name via `TopologyNaming.queueName(mapping.routingKey())` — the same
   convention the topology auto-configuration already uses, not a second naming scheme.
4. Registers a `MethodRabbitListenerEndpoint` against the existing `"rabbitListenerContainerFactory"`
   bean — the same factory every `@RabbitListener` endpoint used.
5. Explicitly builds and sets a `DefaultMessageHandlerMethodFactory` on each endpoint.
   `RabbitListenerAnnotationBeanPostProcessor` sets this only on the endpoints it builds itself
   from `@RabbitListener`; an endpoint registered programmatically via
   `RabbitListenerEndpointRegistrar.registerEndpoint(...)` is never given one automatically
   (verified by reading `RabbitListenerEndpointRegistrar`'s `registerAllEndpoints()`, which
   resolves only the container factory). Omitting this throws `NullPointerException` on first
   message delivery — caught by the `OrderCreatedIT`/`CustomerRegisteredIT` round-trip tests
   during implementation.

`OrderEventListener`/`CustomerEventListener` migrate fully to `@BitsEventHandler` — no methods left
on the old annotation. The now-dead `*_QUEUE` constants (`CREATED_QUEUE`, `REGISTERED_QUEUE`,
etc.) are removed from `OrderEventRouting`/`CustomerEventRouting`; `*_DLQ` and `*_ROUTING_KEY`
constants stay (still referenced by `DlxRoutingIT`, topology/type-mapping autoconfig, the poison
demo).

## Consequences

- First use of `RabbitListenerConfigurer`/programmatic endpoint registration in this codebase —
  a new pattern, not an extension of an existing one. Anyone adding a new listener from now on
  writes `@BitsEventHandler` on a bare event-typed method rather than `@RabbitListener(queues = ...)`.
- DLQ/retry failure routing (`EventConsumerSupport`, `DlxMessageRecoverer`, `DlxRoutingAdvice`) is
  unaffected: it is wired as an advice chain on the shared `rabbitListenerContainerFactory` bean, a
  factory-level property inherited by any endpoint bound to that factory regardless of how the
  endpoint was registered.
- A handler method's single parameter type is now load-bearing at startup: renaming or removing
  the `TypeMapping` for a type a `@BitsEventHandler` method depends on breaks listener registration
  with a fail-fast `IllegalStateException`, matching the project's existing fail-fast philosophy
  for startup misconfiguration (`LocalSchemaCatalog`, `EventPublisher`).
