# 06 — ADR + onboarding docs

**What to build:** Document the landed design. New ADR partially supersedes ADR-0007's manual
per-event `@Bean` decision while retaining named override semantics, `Mappings`, and ADR-0008
exchange ownership. Update glossary / README / tutorial for new-event onboarding: create record in
the domain event package, add both annotations (reuse `*EventRouting` constants), build, commit
generated schema — do not commit the index. Cover index lifecycle, deterministic format, override
vs `TypeMappingSelection`, exact-package invariant, and build/startup failure modes.

**Blocked by:** 05 — Migrate customer-contracts (second domain E2E)

**Status:** done

- [x] ADR exists and correctly scopes what supersedes vs what stays from ADR-0007 / ADR-0008.
- [x] `CONTEXT.md`, `README.md`, and `docs/TUTORIAL.md` describe annotation-driven mappings and
      new-event onboarding without mapping `@Bean` methods.
- [x] Index called out as build-output only (not committed); override and selection knobs distinguished.
- [x] Docs match shipped behavior (no runtime scan; publisher topology still opt-in).
