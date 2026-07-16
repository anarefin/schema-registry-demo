# 05 — Bootstrap workflow discovers artifacts from contracts

**What to build:** The schema-governance bootstrap GitHub Actions workflow stops telling maintainers to keep a hardcoded `ARTIFACTS` list in sync with the deleted `GeneratedSchemas.ALL` class. Artifacts are discovered from `*-contracts` POM `<artifacts>` and/or `schemas/*.schema.json` directories so bootstrap cannot drift from the contracts modules.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Bootstrap workflow no longer references deleted `GeneratedSchemas.ALL`
- [x] `ARTIFACTS` (or equivalent) is derived from contracts schemas and/or POM artifact coordinates
- [x] Rule-attach / verify loops still cover all seven order + customer artifacts under FORWARD
