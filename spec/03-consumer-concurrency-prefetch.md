# 03 — Configurable consumer concurrency & prefetch

**Severity:** High · **Findings:** CONC-001 + SCAL-001 · **Type:** AFK

## What to build

`SchemaMessagingConsumerAutoConfiguration`'s `SimpleRabbitListenerContainerFactory` sets the
converter, `defaultRequeueRejected=false`, and the advice chain, but never sets
`concurrentConsumers` / `maxConcurrentConsumers` / `prefetchCount`. Each per-service queue is
therefore drained by a single consumer thread with default prefetch — a hard per-queue throughput
ceiling regardless of available cores, and the primary bottleneck for the 1k–10k tiers.

Expose concurrency and prefetch as configuration with sensible bounded defaults and set them on the
factory. Thread-safety itself is already sound (stateless converter/strategies), so this is purely a
tuning-surface change.

## Acceptance criteria

- [ ] `concurrentConsumers`, `maxConcurrentConsumers`, `prefetchCount` are externally configurable.
- [ ] Sensible bounded defaults applied (e.g. 2–8 consumers, prefetch ≈ 10× consumers).
- [ ] Defaults documented alongside the other `events.*` properties.
- [ ] Thread-safety unaffected (converter/strategies remain stateless).
- [ ] Load-test verification notes captured to size the defaults.

## Blocked by

None — can start immediately. (Pairs well with #05 virtual threads.)
