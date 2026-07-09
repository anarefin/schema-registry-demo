# ADR-0001: Code-First Schema Generation

## Status

Accepted (retroactively documented — the decision predates this ADR; see
`spec/code-first-schema.md` for the full specification).

## Context

The POC needed a single source of truth for the six event contracts (three orders, three
customers) that is both developer-ergonomic and safe to evolve under CI-enforced compatibility
gates. Two approaches were on the table:

- **Schema-first**: hand-author JSON Schema, generate Java POJOs from it (`jsonschema2pojo`).
- **Code-first**: hand-author Java records (with `jakarta.validation` annotations) as the source
  of truth, generate the JSON Schema from them at build time.

An earlier schema-first approach, and a throwaway `payment-contracts` pilot built on it, were
already in the repository and are superseded by this decision.

## Decision

Adopt **code-first**: Java `record`s are authored by developers and are the contract's source of
truth. `schema-gen-tools` (a build-only module using victools) generates the corresponding JSON
Schema deterministically at `process-classes` time; the generated `*.schema.json` is committed
alongside the record and never hand-edited. A CI drift gate re-generates and diffs to catch any
schema that fell out of sync with its record.

Each `*-contracts` module (`order-contracts`, `customer-contracts`) holds the records, the
generated schemas, and routing constants. `schema-messaging-core` provides the reusable,
domain-agnostic runtime plumbing (schema-aware message converter, registry resolver) shared by
every domain via `TypeMapping` beans — never duplicated per contract.

## Consequences

- Developers work in one language (Java) for the contract; the JSON Schema is a build artifact,
  not something anyone edits by hand.
- Determinism (byte-stable generation) is required so the drift gate is meaningful — enforced by
  `SchemaDeterminismTest`.
- Adding a seventh event means adding one record + one `TypeMapping`, not a hand-authored schema.
- This ADR's runtime-wiring narrative (`spec/code-first-schema.md` D3, Guiding Principles 4–5) —
  specifically that AMQP topology is transport-agnostic and centralized in
  `schema-messaging-core` — was later reversed. See
  [ADR-0002](0002-contract-owned-amqp-topology.md); the schema-generation decision recorded here
  (D1/D2/D4/D5/D6) is unaffected and remains current.
