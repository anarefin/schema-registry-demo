# 04 — Remove runtime index machinery + stop writing the .idx

**What to build:** With both domains on generated config, the runtime index-driven registration is
dead code and gets removed. `event-contract-kit` loses `RegisterEventMappings`,
`EventMappingRegistrar`, `IndexedEventMappings`, `EventMappingIndexReader`, and
`EventMappingRegistrationException`, plus their tests and fixtures (`IndexedEventMappingsTest`,
`EventMappingRegistrarTest`, `EventMappingIndexReaderTest`, `IndexClassLoaders`, `indexfixtures/**`).
`schema-gen-tools` loses `EventMappingIndexWriter` (+ its test) and the
`EventMappingIndexWriter.write(...)` call is dropped from `SchemaGeneratorCli` — schema generation
and the `EventMappingValidator` fail-fast stay. After this, `META-INF/event-mappings.idx` is no
longer written or read anywhere. `TypeMappingRegistry` retains its cross-jar duplicate-coordinate
and duplicate-`javaType` guards; the cross-jar bean-name-collision guard is intentionally gone
(documented in ticket 05's ADR).

**Blocked by:** 03 — Migrate customer-contracts to generated config

**Status:** ready-for-agent

- [ ] `RegisterEventMappings`, `EventMappingRegistrar`, `IndexedEventMappings`,
      `EventMappingIndexReader`, `EventMappingRegistrationException` deleted from `event-contract-kit`
      along with their tests/fixtures.
- [ ] `EventMappingIndexWriter` + `EventMappingIndexWriterTest` deleted; `SchemaGeneratorCli` no
      longer calls `.write(...)`; schema gen + `EventMappingValidator` fail-fast retained.
- [ ] No code writes or reads `META-INF/event-mappings.idx`; no dangling references anywhere.
- [ ] `TypeMappingRegistry` still throws on cross-jar duplicate coordinates and duplicate `javaType`.
- [ ] `./mvnw -pl event-contract-kit,order-contracts,customer-contracts,schema-messaging-core -am test`
      passes.
