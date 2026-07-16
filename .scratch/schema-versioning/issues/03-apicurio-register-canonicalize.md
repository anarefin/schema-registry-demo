# 03 — Apicurio register uses canonicalize

**What to build:** Contract-module Apicurio register config sets `<canonicalize>true</canonicalize>` so `FIND_OR_CREATE_VERSION` matches on canonical content, not raw bytes. Re-registering unchanged schemas must not create spurious new Apicurio versions. The existing offline git drift gate remains unchanged and green.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `<canonicalize>true</canonicalize>` is set for order-contracts and customer-contracts Apicurio register configuration (pluginManagement and/or per-module)
- [ ] Double `apicurio-registry:register` against a running registry on unchanged schemas creates no new versions
- [ ] Offline drift gate still works: `process-classes` + `git diff --exit-code` on `*-contracts` schemas
