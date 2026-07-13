# 03 — DLX/retry exchange naming parity enforced

**What to build:** Domain routing constants for DLX and retry exchange names and the runtime naming convention used by per-service topology declaration and failure routing must share a single source of truth. A CI test must fail if the two paths ever diverge, so a naming convention change cannot silently route failed messages to an exchange no queue is bound to.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Order and customer domain DLX/retry exchange names are derived from the same convention as runtime topology naming, or a parity test asserts they stay identical
- [ ] Test fails if either domain's literal constants disagree with the shared naming helper
- [ ] No change to actual exchange names on the wire (behaviour-preserving refactor)
