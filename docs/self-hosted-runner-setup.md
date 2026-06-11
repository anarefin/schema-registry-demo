# Self-Hosted Runner + Standing Local Registry — Setup Runbook

> **Refactor note (June 2026):** this POC has been refactored to be **JSON Schema-only**.
> `OrderCreated` was converted from Protobuf to a JSON Schema artifact (generated POJO via
> jsonschema2pojo) and now carries a **FORWARD** compatibility rule, same as
> `CustomerRegistered`. Protobuf/BACKWARD passages below predate the refactor — treat them as
> historical/educational context; the schema files are now `order-created*.json` and the wire
> format is always `application/json`.

> **Goal:** run **one always-on Apicurio + Postgres registry** as containers on your machine and
> have GitHub Actions use it. Because your machine is behind NAT (no public inbound), a
> GitHub-hosted runner can't reach it — so we run a **self-hosted runner on the same machine**.
> Jobs then reach the registry at `http://localhost:8080`.
>
> **Audience:** the maintainer of a **private** `schema-registry-demo` repo on macOS with Docker
> Desktop, JDK 25, and the committed `./mvnw` wrapper.

---

## How it fits together

```
Your machine (always-on)
├─ docker compose ──► apicurio :8080 ── postgres (pgdata volume, persistent)   ← the ONE registry
│                         └─ host `./mvnw register` + rule curls seed both artifacts after `up`
├─ self-hosted GitHub runner  (label: apicurio-local)  ──reaches──► http://localhost:8080
└─ local dev ── ./mvnw ... -Dapicurio.registry.url=http://localhost:8080
```

The three workflows now run on `[self-hosted, apicurio-local]`:

| Workflow | Trigger | Access | What it does |
|----------|---------|--------|--------------|
| `schema-compat-check.yml` | PR touching a contract | **read-only** (dry-run) | Validates the PR schema against the registry's real history. Writes nothing. |
| `schema-register.yml` | push to `main` touching a contract | **write** | Registers the new version(s). |
| `schema-governance-bootstrap.yml` | manual | **write** | Attaches FORWARD rules (idempotent). |

> **Key property:** PRs only *read*; only merges (and the manual bootstrap) *write*. So opening or
> updating a PR can never mutate the standing registry.

---

## 1. Prerequisites

1. **Docker Desktop** running.
2. **JDK 25** + `~/.m2/toolchains.xml` with a JDK-25 entry (already present on this machine).
3. The standing registry up and seeded:

   ```bash
   docker compose up -d
   ```

   Once `apicurio` is healthy, register both artifacts and attach their compatibility rules from
   the host. Both artifacts are JSON Schema and take FORWARD (Apicurio rejects JSON property
   additions under BACKWARD as a "narrowing"):

   ```bash
   ./mvnw -pl order-contracts,customer-contracts apicurio-registry:register \
          -Dapicurio.registry.url=http://localhost:8080
   curl -s -o /dev/null -X POST \
     "http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules" \
     -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
   curl -s -o /dev/null -X POST \
     "http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules" \
     -H 'Content-Type: application/json' -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'
   ```

   Verify:

   ```bash
   # Both artifacts return FORWARD
   curl -sf http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules
   curl -sf http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules
   ```

   Thanks to the `pgdata` volume and `restart: unless-stopped`, the registry and its data come
   back automatically after a reboot (as long as Docker Desktop is running).

---

## 2. Install the self-hosted runner

1. In GitHub: **repo → Settings → Actions → Runners → New self-hosted runner**.
2. Select **macOS** + your architecture (arm64 for Apple Silicon). GitHub shows exact
   download/config commands with a one-time token. They look like:

   ```bash
   mkdir -p ~/actions-runner && cd ~/actions-runner
   curl -o actions-runner-osx-arm64.tar.gz -L https://github.com/actions/runner/releases/download/<version>/actions-runner-osx-arm64-<version>.tar.gz
   tar xzf actions-runner-osx-arm64.tar.gz

   # IMPORTANT: add the apicurio-local label so the workflows target this runner
   ./config.sh --url https://github.com/<you>/schema-registry-demo --token <TOKEN> --labels apicurio-local
   ```

3. Run it **as a service** so it's always available (survives logout/reboot):

   ```bash
   ./svc.sh install
   ./svc.sh start
   ./svc.sh status     # should show "started"
   ```

4. Confirm **repo → Settings → Actions → Runners** shows the runner as **Idle** with the
   `self-hosted` and `apicurio-local` labels.

> If you prefer a foreground run for testing: `./run.sh` (Ctrl-C to stop). Use the service for
> day-to-day.

---

## 3. Security (keep it safe)

- **Keep the repo PRIVATE while a self-hosted runner is registered.** A self-hosted runner on a
  **public** repo is a remote-code-execution risk: a fork PR could run arbitrary code on your
  machine. (This reverses the earlier "keep it public for free minutes" note in
  `docs/github_ci_steps.md` — with a self-hosted runner you no longer need hosted minutes.)
- Limit who can push/edit workflows in the repo.
- The standing registry holds only POC data and is bound to `localhost`. Don't expose `:8080`
  publicly. If you later need remote access, add OIDC auth + a read-only CI token (see
  `docs/apicurio-best-practices.md` §5.5 / §8).

---

## 4. Verify the end-to-end loop

1. **Compatible PR.** Branch, add a backward-compatible field, e.g. in
   `order-contracts/src/main/resources/schemas/order-created.proto`:

   ```proto
   optional string notes = 9;   // backward-compatible addition
   ```

   Push, open a PR. The **Schema Compatibility Check** runs on `self-hosted` and **passes**.

2. **No-write proof.** Before and after that PR check, the version count is unchanged (dry-run):

   ```bash
   curl -sf http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/versions
   ```

3. **Incompatible PR.** Change `string order_id = 1;` → `int64 order_id = 1;` and open a PR →
   the check **fails** with a compatibility rejection (merge blocked under branch protection).

4. **Merge.** Merge a compatible contract change to `main` → **Schema Registration** runs on
   `self-hosted` and registers the new version. Confirm in the Apicurio UI
   (<http://localhost:8888>) or via the `.../versions` REST call above.

---

## 5. Operational notes & limits

- **Availability = your machine.** PR checks and registration run only when the machine, Docker
  Desktop, and the runner service are all up. If the machine is off, jobs **queue** until it's
  back — they don't fail.
- **Branch protection** still works: point the required status check at *Compatibility check
  (orders + customers)*.
- **Scaling past one laptop:** the same workflows work unchanged on an always-on VM — install the
  runner there with the same `apicurio-local` label and run the registry on that host. Nothing in
  the workflows is laptop-specific beyond the runner location.
- **Resetting the registry:** `docker compose down -v` removes the `pgdata` volume (wipes
  registered schemas); `docker compose up -d` re-seeds via the registrar.
