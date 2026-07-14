# 10 — Extract domain topology + mapping factories into `event-contract-kit`

**Severity:** Low · **Finding:** ARCH-006 (related: QUAL-002) · **Type:** AFK

## What to build

`order-contracts` and `customer-contracts` are structurally identical: three `TopicExchange` `@Bean`s
on the `main`/`.dlx`/`.retry.exchange` pattern, plus one `TypeMapping` `@Bean` per event. Onboarding a
third domain means copy-pasting ~65 lines with only names changed, and the two copies can drift. The
review carried two verdicts — ARCH-006 (extract now) vs QUAL-002 (wait for a third domain). Grounding
in the code confirmed the extraction is low-risk, so we **extract now**:

- Nothing downstream binds to the individual bean *names*: `ServiceQueueTopologyAutoConfiguration`
  injects `List<TopicExchange>` and indexes by `exchange.getName()`; `TypeMappingRegistry` takes
  `List<TypeMapping>`.
- The factories land in `event-contract-kit`, which **both** contracts modules already depend on and
  which is already spring-amqp-aware — so this does **not** touch the `core ↛ contracts` isolation rule.

Build two domain-agnostic factories in `event-contract-kit` and reduce each module's topology/mapping
config to one-line delegations (**Shape 1: delegate-per-bean, zero core changes** — each module keeps
one `@Bean` per exchange/event, preserving RabbitAdmin auto-declaration, `List` collection injection,
and per-event `@ConditionalOnMissingBean` override):

**`com.example.amqp.topology.DomainTopology`** (+ `DomainExchanges(main, dlx, retry)` record):
`DomainTopology.of(mainExchangeName)` builds the three `new TopicExchange(name, true, false)`,
deriving DLX/retry via the existing parity-enforced `TopologyNaming.dlxExchangeName` /
`retryExchangeName` helpers — no second derivation site.

**`com.example.amqp.topology.mapping.Mappings`** — domain-scoped builder:
`Mappings.forDomain(group, exchange)` binds group + exchange + `SchemaType.JSON` once;
`json(javaType, routingKey)` defaults `artifactId` to `javaType.getSimpleName()`, with a
`json(javaType, artifactId, routingKey)` overload for future mismatches. All seven current artifactIds
already equal the class simple name, so this is behavior-preserving and closes the string↔class drift
vector.

Each `*TopologyAutoConfiguration` / `*TypeMappingAutoConfiguration` then holds one
`DomainTopology.of(...)` / `Mappings.forDomain(...)` and returns `EX.main()`/`EX.dlx()`/`EX.retry()` and
`M.json(...)` from its existing `@Bean` methods. Bean names, `@ConditionalOnMissingBean`, and the
`*EventRouting` constants stay as-is; the `coords()` helper and unused imports are deleted.

**Explicitly rejected — Shape 2 (aggregate `Declarables`/`TypeMappingSet` beans):** forces changes into
`TypeMappingRegistry` + `ServiceQueueTopologyAutoConfiguration` (the class already flagged by
SB-001/ARCH-008), loses per-event override, for marginal gain. Do not build this now.

## Acceptance criteria

- [ ] `DomainTopology.of(mainName)` returns three `TopicExchange`s (`durable=true, autoDelete=false`)
      whose names match `TopologyNaming` derivation; unit-tested in `event-contract-kit`.
- [ ] `Mappings.forDomain(group, exchange).json(...)` sets group/`JSON`/exchange, defaults artifactId to
      the class simple name, and honors the explicit-id overload; unit-tested in `event-contract-kit`.
- [ ] Both contracts modules delegate to the factories; `coords()` helpers and now-unused
      `SchemaCoordinates`/`SchemaType` imports removed. Bean names + `@ConditionalOnMissingBean` guards
      unchanged.
- [ ] A per-module test asserts `coordinates().artifactId().equals(javaType().getSimpleName())` for
      every `TypeMapping`, locking the defaulted-id intent.
- [ ] `schema-messaging-core` is unchanged.
- [ ] Behavior-preserving: `process-classes` regenerates identical schemas
      (`git diff --exit-code -- '*-contracts/src/main/resources/schemas/*'`), and `./mvnw clean verify`
      (Testcontainers ITs) passes — exchanges still auto-declare and a message flows end-to-end.
- [ ] Docs: new `docs/adr/ADR-0007-domain-topology-mapping-factories.md` (context, decision, rejected
      alternatives); `CONTEXT.md` glossary gains `DomainTopology`/`DomainExchanges`/`Mappings`; the
      `event-contract-kit` / `*-contracts` bullets in `CLAUDE.md` note the new factories.

## Blocked by

None — can start immediately.
