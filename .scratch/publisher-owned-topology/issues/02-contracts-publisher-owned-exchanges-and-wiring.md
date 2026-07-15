# 02 — Flip contracts to publisher-owned exchanges + wire the two services

**What to build:** Make exchange ownership follow domain cardinality: the **one** publisher of a
domain declares that domain's exchanges; consumers declare only their private queues and *bind* to
the publisher-owned exchanges. A contracts jar alone no longer forces any service to declare
exchanges.

Each `*-contracts` module replaces its auto-imported `*TopologyAutoConfiguration` with a plain,
**opt-in** `*PublisherTopology` `@Configuration` — the same three exchange `@Bean`s
(`main`/`dlx`/`retry` from `DomainTopology.of(*EventRouting.EXCHANGE)`, each
`@ConditionalOnMissingBean(name=...)`) but **not** listed in
`AutoConfiguration.imports`. `*TypeMappingAutoConfiguration` stays auto-loaded (plain-data beans
both roles need). Records, schemas, and `*EventRouting` constants are unchanged.

`producer-service` (sole publisher of both domains) `@Import`s both `*PublisherTopology` and thus
declares all 6 exchanges at startup. `consumer-service` production code is unchanged — a context
with a contracts jar but no `@Import` and no `@BitsEventHandler` now declares no broker topology at
all — so its Testcontainers ITs `@Import` a `*PublisherTopology` (or a tiny test config) to provide
the exchanges their bindings need.

**Blocked by:** 01 — Core declares only queues + bindings.

**Status:** ready-for-agent

- [ ] Each `*-contracts` module exposes an opt-in `*PublisherTopology` (`main`/`dlx`/`retry`
      `@Bean`s from `DomainTopology.of(...)`, `@ConditionalOnMissingBean(name=...)`) and no longer
      lists a topology class in `AutoConfiguration.imports`; `*TypeMappingAutoConfiguration` still auto-loads.
- [ ] New core `ApplicationContextRunner` regression test: contracts jar on classpath, no
      `@Import(*PublisherTopology)` and no `@BitsEventHandler` ⇒ **zero** `Declarable` beans and
      **zero** `declareExchange` invocations.
- [ ] `producer-service` `@Import`s both `*PublisherTopology` and declares all 6 exchanges at startup.
- [ ] `consumer-service` production code unchanged; its Testcontainers ITs import a
      `*PublisherTopology`/test config so exchanges exist for bindings.
- [ ] A consumer that boots before the producer comes up cleanly (resilient declaration) and its
      queues bind once the producer declares the exchanges.
- [ ] `./mvnw clean verify` passes: schema drift check clean
      (`git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'`), ITs flow a message
      end-to-end, and the legacy-decommission IT stays green.
