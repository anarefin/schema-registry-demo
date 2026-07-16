# 04 — Expand/contract + deprecation playbook

**What to build:** A short `docs/EXPAND-CONTRACT.md` that teaches the sanctioned Tier 1 path: add optional field → regenerate → commit schema → FORWARD compat-check → deploy producer first → consumers adopt → deprecate/remove only with ACK from all handler owners. Include the OrderCreated `priority` worked example, a contracts-PR checklist (FORWARD? optional? producer deploy order? consumers listed?), and the CI/human-only Apicurio `DEPRECATED` version-state curl — runtime never fetches registry content for this signal.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `docs/EXPAND-CONTRACT.md` exists with OrderCreated-style expand → migrate → contract steps
- [ ] PR checklist for contracts changes is documented (FORWARD, optional fields, producer-first, consumer inventory)
- [ ] Deprecation playbook documents version-state `DEPRECATED` via registry REST for humans/CI only, plus owner signal via ticket/CODEOWNERS — not via consumer JVM
- [ ] Anti-example called out: in-place rename / same-artifact breaking change under FORWARD is rejected
