# 06 — Parse JSON once per message

**Severity:** Medium · **Finding:** PERF-001 (resolves GC-001) · **Type:** AFK

## What to build

`JsonSchemaStrategy` parses each payload twice. On consume with `validateOnDeserialize=true` (the
default), `validate()` calls `objectMapper.readTree(bytes)` and then `deserialize()` calls
`objectMapper.readValue(bytes, type)` — the payload is fully parsed twice. On produce, `serialize()`
does `writeValueAsBytes(payload)` then `validate()` re-parses those bytes with `readTree`. This
doubles parse CPU and short-lived allocation for every message.

Parse once and reuse the `JsonNode`, without changing validation/serialization semantics.

## Acceptance criteria

- [ ] Consume path: `readTree(bytes)` → validate node → `convertValue(node, targetType)` (single parse).
- [ ] Produce path: `valueToTree(payload)` → validate node → `writeValueAsBytes(node)` (single serialize).
- [ ] Validation and serialization semantics unchanged; existing tests pass.
- [ ] Resolves the associated young-gen allocation churn (GC-001) as a side effect.

## Blocked by

None — can start immediately.
