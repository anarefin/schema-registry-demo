# 02 — Build-time index + paired-annotation validation in schema-gen-tools

**What to build:** The existing contracts `process-classes` generator path discovers
`@GenerateSchema` types, requires a paired `@EventMapping`, validates type shape and effective
metadata, still emits JSON Schemas unchanged, and atomically writes a deterministic
`META-INF/event-mappings.idx` under build output only (UTF-8, one FQCN per line, lexically sorted,
trailing newline). Index is never committed under `src/main/resources`. Tools stay free of
compile deps on contracts / kit — annotations matched by FQCN and read reflectively. Fixture tests
cover reject paths; no contracts module is migrated yet.

**Blocked by:** 01 — `@EventMapping` + bean-name helper in event-contract-kit

**Status:** ready-for-agent

- [ ] Paired `@GenerateSchema` + `@EventMapping` enforced; unpaired types fail the build.
- [ ] Rejects blank required attrs, unsupported type shapes (nested/local/abstract/interface/enum/
      non-public/non-record), duplicate Java types, duplicate effective `(groupId, artifactId)`,
      and deterministic bean-name collisions.
- [ ] Index written only under `${project.build.outputDirectory}/META-INF/`; atomic stale replace;
      no static init during discovery.
- [ ] Existing schema output remains byte-for-byte unchanged for current fixtures / regenerations.
- [ ] `./mvnw -pl schema-gen-tools test` passes.
