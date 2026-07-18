# ADR-0006: TypeMapping relocated to event-contract-kit (tombstone)

**Status:** Accepted (decision still in force) — original file deleted in `c0f9738`; this tombstone
restores the citation target.  
**See also:** [ADR numbering eras](README.md), [ADR-0007](ADR-0007-domain-topology-mapping-factories.md)

## Decision (unchanged)

`TypeMapping`, `SchemaCoordinates`, `SchemaType`, and `Mappings` live in **`event-contract-kit`**
(domain-agnostic leaf), not in `schema-messaging-core`. Both `*-contracts` and core depend on the
kit; neither direction `contracts ↔ core` is allowed (enforcer on both sides).

This restored Principle 2 of contract-owned topology: contracts never need a dependency on core to
declare mapping beans.
