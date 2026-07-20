# ADR numbering eras

**Status:** Informational  
**Date:** 2026-07-17

This directory has two numbering eras. Do not confuse them.

## Pre-`c0f9738` era (deleted originals)

Original ADRs `0001`–`0008` lived under `docs/adr/` before commit `c0f9738`. Several were deleted
while still cited from POMs/Javadoc. Survivors / restorations:

| Path | Status |
|---|---|
| [0004-local-schema-validation.md](0004-local-schema-validation.md) | Restored (classpath schemas) |
| [0003-contracts-own-schema-generation.md](0003-contracts-own-schema-generation.md) | Tombstone → still true; see note in file |
| [0005-contracts-may-depend-on-core.md](0005-contracts-may-depend-on-core.md) | Tombstone → **superseded** (contracts must not depend on core) |
| [0006-typemapping-relocated-to-event-contract-kit.md](0006-typemapping-relocated-to-event-contract-kit.md) | Tombstone → decision still stands |

## Current era (ADR-0007+)

After the delete, new decisions reused numbers under the `ADR-NNNN-*.md` filename style:

| Path | Topic |
|---|---|
| [ADR-0007-domain-topology-mapping-factories.md](ADR-0007-domain-topology-mapping-factories.md) | `DomainTopology` / `Mappings` factories (mapping `@Bean` half superseded by ADR-0010) |
| [ADR-0008-publisher-owned-messaging-topology.md](ADR-0008-publisher-owned-messaging-topology.md) | Publisher-owned exchanges / consumer queues |
| [ADR-0009-schema-versioning-model.md](ADR-0009-schema-versioning-model.md) | Tier-1 compatible evolution only |
| [ADR-0010-annotation-driven-event-mappings.md](ADR-0010-annotation-driven-event-mappings.md) | `@EventMapping` + build-time index → named `TypeMapping` beans (runtime index-driven registration superseded by ADR-0011) |
| [ADR-0011-build-generated-type-mapping-config.md](ADR-0011-build-generated-type-mapping-config.md) | `@EventMapping` → build-time annotation processor emits `@Bean TypeMapping` methods (no runtime index/reflection) |

**Rule:** citations to bare `ADR-0003` / `0005` / `0006` mean the pre-`c0f9738` decisions (see
tombstones). Citations to `ADR-0007` / `ADR-0008` / `ADR-0009` / `ADR-0010` mean the **current**
files in this directory — not the deleted pre-era ADRs that once shared those numbers.
