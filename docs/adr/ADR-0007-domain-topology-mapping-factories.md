# ADR-0007: Domain topology and mapping factories in `event-contract-kit`

**Status:** Accepted (superseded in part by [ADR-0008](ADR-0008-publisher-owned-messaging-topology.md))  
**Date:** 2026-07-14  
**Spec:** `spec/10-domain-topology-mapping-factories.md`  
**Related findings:** ARCH-006 (extract now), QUAL-002 (wait for third domain — rejected)

> **Note (ADR-0008):** the `DomainTopology` / `Mappings` factories introduced here are unchanged and
> still used. What ADR-0008 reverses is *who declares the exchange beans and whether they auto-load*:
> the `*TopologyAutoConfiguration` classes described below (auto-declared for every dependent) were
> replaced by opt-in `*PublisherTopology` configs declared only by the domain's single publisher.
> Read the "Each `*TopologyAutoConfiguration`…" passages below as historical.

## Context

`order-contracts` and `customer-contracts` each declare three `TopicExchange` beans (main /
DLX / retry) and one `TypeMapping` bean per event. The two modules are structurally identical
modulo domain names — ~65 lines of copy-paste that can drift on onboarding a third domain.

Nothing downstream binds to individual bean *names*:

- `ServiceQueueTopologyAutoConfiguration` injects `List<TopicExchange>` and indexes by
  `exchange.getName()`.
- `TypeMappingRegistry` takes `List<TypeMapping>`.

Both contracts modules already depend on `event-contract-kit`, which is spring-amqp-aware. The
`core ↛ contracts` isolation rule is unaffected.

All seven current `artifactId` values already equal `javaType.getSimpleName()`.

## Decision

Extract two domain-agnostic factories into `event-contract-kit` and reduce each contracts
module's auto-config to one-line delegations (**Shape 1: delegate-per-bean, zero core changes**).

### `DomainTopology` + `DomainExchanges`

`DomainTopology.of(mainExchangeName)` returns a `DomainExchanges(main, dlx, retry)` record
holding three `new TopicExchange(name, true, false)` instances. DLX and retry names are derived
via the existing parity-enforced `TopologyNaming.dlxExchangeName` / `retryExchangeName` helpers —
no second derivation site.

Each `*TopologyAutoConfiguration` holds one `DomainTopology.of(...)` and returns
`EX.main()` / `EX.dlx()` / `EX.retry()` from its existing `@Bean` methods.

### `Mappings`

`Mappings.forDomain(group, exchange)` binds group + exchange + `SchemaType.JSON` once.
`json(javaType, routingKey)` defaults `artifactId` to `javaType.getSimpleName()`; a
`json(javaType, artifactId, routingKey)` overload exists for future mismatches.

Each `*TypeMappingAutoConfiguration` holds one `Mappings.forDomain(...)` and returns
`M.json(...)` from its existing `@Bean` methods.

Bean names, `@ConditionalOnMissingBean` guards, and `*EventRouting` constants stay unchanged.
The per-module `coords()` helper and now-unused `SchemaCoordinates` / `SchemaType` imports are
deleted.

## Rejected alternatives

### Shape 2: aggregate `Declarables` / `TypeMappingSet` beans

A single bean returning all exchanges or all mappings would:

- Force changes into `TypeMappingRegistry` and `ServiceQueueTopologyAutoConfiguration` (already
  flagged by SB-001 / ARCH-008).
- Lose per-event `@ConditionalOnMissingBean` override.
- Offer marginal gain over Shape 1.

Not built now.

### Wait for a third domain (QUAL-002)

Duplication is already present and low-risk to extract. Waiting would let the two copies drift
further with no benefit.

## Consequences

- **Positive:** One derivation site for exchange triplets and `TypeMapping` construction;
  third-domain onboarding is a handful of one-line `@Bean` methods instead of ~65 copied lines.
- **Positive:** `artifactId` defaulting to `javaType.getSimpleName()` is centralized, closing
  the string↔class drift vector.
- **Neutral:** `schema-messaging-core` unchanged.
- **Neutral:** RabbitAdmin auto-declaration, `List` collection injection, and per-event override
  semantics preserved.
