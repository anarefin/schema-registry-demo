# 06 — ADR-0009 Open work honesty

**What to build:** ADR-0009 gains an **Open work** section that is honest about runtime hardening status: QUAL-003 / dedicated messaging mapper (Phase 1), generator `additionalProperties` invariant, and canonicalize — with status reflecting what tickets 01–03 actually landed. Architecture stays Accepted; wording makes clear “Accepted architecture; runtime hardening tracked,” not “production hardening complete” until Phase 1 DoD is met.

**Blocked by:** 01 — Defend tolerant reader; 02 — Generator additionalProperties invariant; 03 — Apicurio register canonicalize.

**Status:** ready-for-agent

- [ ] ADR-0009 has Open work pointing at Phase 1 / QUAL-003 (and related hardening) with accurate done vs remaining
- [ ] Status note distinguishes accepted architecture from runtime production-ready claim
- [ ] No implication that DEPRECATED or wire version/hash gates consumers at runtime
