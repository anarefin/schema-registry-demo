# 02 — Cut producer over to generated topology; delete hand-written classes

**What to build:** Flip the sole publisher onto the generated topology and remove the hand-written
duplicates. `producer-service` `@Import`s the generated `OrdersPublisherTopology` and
`CustomersPublisherTopology` instead of the hand-written `OrderPublisherTopology` /
`CustomerPublisherTopology`, which are deleted. The producer still declares all six exchanges
(main/DLX/retry per domain) at startup exactly as before. The contracts topology-context tests and
the consumer's `PublisherOwnedExchanges` support are updated to reference the generated class names.
Bean names, broker exchange names, and every ownership invariant are preserved — verifiable in the
existing test contract. A `consumer-service` still imports no publisher topology, and a contracts
jar alone (mapping auto-config only) declares zero exchanges.

**Blocked by:** 01 — Processor emits generated `*PublisherTopology`

**Status:** ready-for-agent

> Part of the single change set with 01 and 03 — they merge together (see `spec/16` Phasing).

- [ ] `producer-service` `@Import`s `OrdersPublisherTopology` and `CustomersPublisherTopology`;
      hand-written `OrderPublisherTopology` and `CustomerPublisherTopology` deleted with no dangling
      references.
- [ ] Contracts topology-context tests import the generated classes and assert: exactly three
      `TopicExchange` beans per domain; existing exchange bean names unchanged; main/DLX/retry broker
      names unchanged; an application bean of the same name wins (`@ConditionalOnMissingBean`); and
      mapping auto-configuration alone declares zero exchanges.
- [ ] Consumer `PublisherOwnedExchanges` support references the generated topology; consumer imports
      no publisher topology.
- [ ] `./gradlew :customer-contracts:test :order-contracts:test :producer-service:test` passes with
      no hand-written publisher-topology class present.
