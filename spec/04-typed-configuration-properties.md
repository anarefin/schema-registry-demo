# 04 — Typed `@ConfigurationProperties` with `List<Duration>` retry tiers

**Severity:** Medium · **Findings:** SPRING-002 + MAINT-001 · **Type:** AFK

## What to build

`events.retry.tier{0,1,2}.ms` and `events.topology.decommission-legacy-queues` are read through
scattered inline `@Value` defaults in multiple beans, losing relaxed binding, IDE metadata, JSR-303
validation, and a single documented properties surface, and duplicating default values across sites.
Separately, the retry ladder is hard-coded to exactly three tiers (`RetryTierProperties` fixes
`tier0Ms, tier1Ms, tier2Ms`) even though `TopologyNaming.tierSuffix` and `EventTopologyFactory`
already iterate `tierTtls.length` and support N tiers — the abstraction promises N tiers the config
can't express.

Introduce a validated `@ConfigurationProperties("events")` type: retry tiers as an ordered
`List<Duration>`, topology flags as nested types. Drive tier declaration off the list so adding or
removing a tier is a config-only change.

## Acceptance criteria

- [ ] A `@Validated @ConfigurationProperties("events")` type registered via `@EnableConfigurationProperties`.
- [ ] Retry tiers bound as `events.retry.tiers` → `List<Duration>` (arbitrary length).
- [ ] `decommission-legacy-queues` and other topology flags bound as nested typed properties.
- [ ] Beans inject the typed properties object; no remaining `@Value` for these keys.
- [ ] `toArray()` / tier declaration derives from the list; a 2- or 4-tier config works without code edits.
- [ ] JSR-303 validation on the properties; relaxed binding + IDE metadata available.

## Blocked by

None — can start immediately.
