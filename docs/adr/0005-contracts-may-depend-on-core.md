# ADR-0005: Contracts may depend on core (tombstone)

**Status:** **Superseded / reversed** — original file deleted in `c0f9738`.  
**See also:** [ADR numbering eras](README.md), [ADR-0006 tombstone](0006-typemapping-relocated-to-event-contract-kit.md)

## Historical decision

An earlier design allowed `*-contracts` to depend on `schema-messaging-core` so topology /
`TypeMapping` types could live in core.

## Current rule (do not follow ADR-0005)

`*-contracts ↛ schema-messaging-core` is **machine-enforced** in each contracts POM
(`maven-enforcer-plugin` bannedDependencies). Contracts depend on `event-contract-kit` only for
shared mapping/topology types. Pattern for auto-config of `TypeMapping` beans without a core
dependency remains in the contracts modules themselves (`*TypeMappingAutoConfiguration`).

Javadoc that still says “pattern established by ADR-0005” means “contracts own TypeMapping
autoconfig” — not “contracts may depend on core.”
