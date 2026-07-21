# ADR-0003: Contracts own schema generation (tombstone)

**Status:** Accepted (decision still in force) — original file deleted in `c0f9738`; this tombstone
restores the citation target.  
**See also:** [ADR numbering eras](README.md)

## Decision (unchanged)

Each `*-contracts` module owns schema generation: at `process-classes` it runs build-only
`schema-gen-tools` (victools) against its own `@GenerateSchema` records and writes committed JSON
Schemas under `src/main/resources/schemas/`. `schema-gen-tools` is never on the service runtime
classpath. Offline drift CI regenerates and `git diff --exit-code`s those files.

## Note

The full pre-`c0f9738` prose is not restored here. Behaviour is described in `CLAUDE.md`,
`README.md`, and each contracts module's `build.gradle` (`generateSchemas` task + determinism tests).
