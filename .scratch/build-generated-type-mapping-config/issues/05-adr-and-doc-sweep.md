# 05 — ADR-0011 + doc sweep + remove superseded Spec 14

**What to build:** Capture the decision and clean up the planning trail. A new
`docs/adr/ADR-0011-build-generated-type-mapping-config.md` records the move to build-time
`TypeMapping` codegen, including the accepted trade-off that the runtime cross-jar
bean-name-collision guard is gone (two domains sharing a Java simple name now silently drop one
mapping via the `@ConditionalOnMissingBean` skip and surface as a lookup miss, not a startup abort).
ADR-0010's runtime-registration section is marked Superseded. `CLAUDE.md`, `CONTEXT.md`, and
`docs/TUTORIAL.md` references to the index/registrar are updated to describe the generated-`@Bean`
flow. The superseded planning is removed: `spec/14-memoize-event-mapping-index-per-classloader.md`
and `.scratch/memoize-event-mapping-index/`. A full clean build confirms producer/consumer boot on
generated beans.

**Blocked by:** 04 — Remove runtime index machinery + stop writing the .idx

**Status:** ready-for-agent

- [ ] `ADR-0011-build-generated-type-mapping-config.md` added, documenting the cross-jar
      bean-name-collision trade-off.
- [ ] ADR-0010 runtime-registration section marked Superseded.
- [ ] `CLAUDE.md`, `CONTEXT.md`, `docs/TUTORIAL.md` no longer describe the index/registrar as the
      runtime path; they describe generated `@Bean TypeMapping` methods.
- [ ] `spec/14-memoize-event-mapping-index-per-classloader.md` and
      `.scratch/memoize-event-mapping-index/` deleted; no dangling references.
- [ ] `./mvnw clean install` green; producer/consumer boot with generated beans.
