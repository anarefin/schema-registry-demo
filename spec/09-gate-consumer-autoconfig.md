# 09 — Gate consumer listener beans out of a pure producer

**Severity:** Low · **Finding:** ARCH-001 · **Type:** AFK

## What to build

`SchemaMessagingConsumerAutoConfiguration` (`schema-messaging-core/.../config/`) is unconditional, so
a pure producer (no `@BitsEventHandler` methods) still instantiates `rabbitListenerContainerFactory`,
`BitsEventHandlerRegistrar`, `DlxMessageRecoverer`, and `DlxRoutingAdvice` that it never uses.
`RabbitAdmin` is legitimately needed by the producer to declare exchanges, but the listener stack is
dead weight and blurs the producer/consumer boundary.

Gate the listener-only beans behind a condition — prefer a `@ConditionalOnProperty("events.consumer.enabled")`,
or split a `...ProducerAutoConfiguration` (RabbitAdmin only) from the consumer one — so a producer's
context stays free of unused listener machinery.

## Acceptance criteria

- [ ] Listener-only beans (`rabbitListenerContainerFactory`, `BitsEventHandlerRegistrar`, `DlxMessageRecoverer`, `DlxRoutingAdvice`) are not instantiated in a service with no `@BitsEventHandler` methods.
- [ ] `RabbitAdmin` (needed for exchange declaration) remains available to the producer.
- [ ] The consumer service still wires the full listener stack unchanged (behavior-preserving).
- [ ] A test asserts the producer context does not contain the listener-only beans.

## Blocked by

None — can start immediately.
