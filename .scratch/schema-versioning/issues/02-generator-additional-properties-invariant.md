# 02 — Generator emits + asserts `additionalProperties: true`

**What to build:** Schema generation makes the tolerant-reader invariant visible and enforceable: every object node in generated JSON Schema carries `"additionalProperties": true`, and a `schema-gen-tools` test walks the parsed tree (not a substring check) and fails if any node carries `"additionalProperties": false`. Committed contract schemas are regenerated; the offline drift gate stays clean.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Generator emits `"additionalProperties": true` on every object node (via victools option / generator config)
- [ ] Test parses schema JSON to a tree, recurses all object/array nodes, and fails if any `additionalProperties` is literally `false`
- [ ] Regenerated `*-contracts` schemas committed; `./mvnw -pl order-contracts,customer-contracts -am process-classes` then `git diff --exit-code` on schemas is clean
- [ ] `./mvnw -pl schema-gen-tools,order-contracts,customer-contracts test` passes
