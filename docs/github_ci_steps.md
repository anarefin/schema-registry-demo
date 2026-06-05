# GitHub CI Setup Guide — Schema Registry Demo

> **What this covers:** wiring up the three GitHub Actions workflows and testing them end to end,
> using the project's current model — a **self-hosted runner** that shares a machine with a
> **standing local Apicurio registry** (`http://localhost:8080`).
>
> **Why self-hosted:** the registry runs as containers on your machine, which is behind NAT and
> unreachable from GitHub-hosted runners. A self-hosted runner on the same machine reaches the
> registry over `localhost`. See [`self-hosted-runner-setup.md`](./self-hosted-runner-setup.md)
> for the runner/registry install runbook — **do that first**; this guide covers the GitHub side.
>
> **Prerequisites:** Docker Desktop, Java 25 JDK + `~/.m2/toolchains.xml`, the `./mvnw` wrapper,
> the standing registry running (`docker compose up -d`), and a **self-hosted runner labelled
> `apicurio-local`** registered and Idle.

---

## Table of contents

1. [How the CI workflows fit together](#1-how-the-ci-workflows-fit-together)
2. [Push the project to GitHub (private)](#2-push-the-project-to-github-private)
3. [Verify GitHub Actions + the runner](#3-verify-github-actions--the-runner)
4. [Set up branch protection (recommended)](#4-set-up-branch-protection-recommended)
5. [End-to-end test: compatible schema change (happy path)](#5-end-to-end-test-compatible-schema-change-happy-path)
6. [End-to-end test: incompatible schema change (blocked PR)](#6-end-to-end-test-incompatible-schema-change-blocked-pr)
7. [Run the bootstrap workflow](#7-run-the-bootstrap-workflow)
8. [Cost & availability model](#8-cost--availability-model)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. How the CI workflows fit together

Three workflow files live in `.github/workflows/`, all running on `[self-hosted, apicurio-local]`
against the standing registry at `http://localhost:8080`:

| Workflow | Trigger | Access | What it does |
|----------|---------|--------|--------------|
| `schema-compat-check.yml` | PR touching `order-contracts/**` or `customer-contracts/**` | **read-only** | Runs `verify -Pcompat-check` (a `register` **dry-run**) — validates the PR schema against the registry's real registered history. **Blocks merge if INCOMPATIBLE.** Writes nothing. |
| `schema-register.yml` | Push to `main` for the same paths | **write** | Registers the new version(s) of both contracts to the registry (idempotent). |
| `schema-governance-bootstrap.yml` | Manual (`workflow_dispatch`) | **write** | Attaches the `BACKWARD` rule to each domain artifact (idempotent). Defaults to `localhost:8080`. |

**Key property:** PRs only *read*; only merges (and the manual bootstrap) *write*. Opening or
updating a PR can never mutate the standing registry. No `DEV_REGISTRY_URL` secret and no
throwaway containers are involved — the registry is the one you run via `docker compose`.

```
Your machine (always-on)
├─ docker compose ──► apicurio :8080 ── postgres (pgdata volume)        ← the ONE registry
│                         └─ host `./mvnw register` + rule curls seed artifacts after `up`
└─ self-hosted runner (apicurio-local) ── reaches → http://localhost:8080

Developer                         GitHub                          self-hosted runner
──────────────────────────────────────────────────────────────────────────────────────
open / update PR  ──────────────► schema-compat-check.yml ───────► mvnw verify -Pcompat-check
                                  (read-only dry-run)               PASS → green / FAIL → blocked

merge to main     ──────────────► schema-register.yml ───────────► mvnw apicurio-registry:register
                                  (write)                           new version registered
```

---

## 2. Push the project to GitHub (private)

### 2a. Create the repository on GitHub

1. Go to <https://github.com/new>
2. Repository name: `schema-registry-demo`
3. **Visibility: Private (required).** A self-hosted runner on a **public** repo is a
   remote-code-execution risk — a fork PR could run arbitrary code on your machine. Keep the repo
   private for as long as a self-hosted runner is registered.
4. Leave "Initialize repository" **unchecked** (the project already has a git history)
5. Click **Create repository**

### 2b. Push all branches

```bash
git remote add origin https://github.com/anarefin/schema-registry-demo.git
git push -u origin main
git push origin feature/github-actions-schema-governance
```

### 2c. Activate the workflows on `main`

Workflows take effect once they're on `main` (or the branch under test):

```bash
git checkout main
git merge feature/github-actions-schema-governance
git push origin main
```

---

## 3. Verify GitHub Actions + the runner

1. Repo → **Actions** tab → confirm the three workflows appear in the sidebar:
   - `Schema Compatibility Check`
   - `Schema Registration`
   - `Schema Governance Bootstrap`
   (If a banner asks to enable Actions, click **"I understand my workflows, go ahead and enable
   them"** — appears once.)
2. Repo → **Settings → Actions → Runners** → confirm your runner shows **Idle** with the
   `self-hosted` and `apicurio-local` labels. If it's missing or offline, finish
   [`self-hosted-runner-setup.md`](./self-hosted-runner-setup.md) first.
3. On the runner machine, confirm the registry is up and seeded:
   ```bash
   curl -sf http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules
   curl -sf http://localhost:8080/apis/registry/v3/groups/events.customers/artifacts/CustomerRegistered/rules
   ```
   Each should return a JSON array containing a `COMPATIBILITY` / `BACKWARD` rule.

---

## 4. Set up branch protection (recommended)

Makes the compat-check a **hard gate** — no PR with an incompatible schema change can merge.

1. **Settings → Branches → Add branch protection rule**
2. Branch name pattern: `main`
3. Check **"Require a pull request before merging"**
4. Check **"Require status checks to pass before merging"**
5. In the status-check search box, select **`Compatibility check (orders + customers)`**
   (one check — the job covers both domains; there are no longer separate per-domain checks).
6. Check **"Require branches to be up to date before merging"**
7. Click **Create**

> The check only appears in the search box after it has run at least once — open a throwaway PR
> (section 5) if you don't see it yet.

---

## 5. End-to-end test: compatible schema change (happy path)

Proves the full loop for a BACKWARD-compatible change.

### 5a. Branch + edit

```bash
git checkout -b test/add-notes-field
```

Add one line at the end of the `OrderCreated` message in
`order-contracts/src/main/resources/schemas/order-created.proto`:

```proto
optional string notes = 9;   // ← BACKWARD-compatible addition
```

```bash
git add order-contracts/src/main/resources/schemas/order-created.proto
git commit -m "test: add optional notes field to OrderCreated"
git push origin test/add-notes-field
```

### 5b. Open a PR against `main`, then watch the check

1. On the PR page, scroll to **Checks** → **Schema Compatibility Check** →
   **Compatibility check (orders + customers)** (a single job on the self-hosted runner).
2. The log shows it run straight to the dry-run — no Postgres/Apicurio startup, no baseline
   seeding (the standing registry already holds the baseline + rules):
   ```
   ✓ Checkout
   ✓ Set up Java 21
   ✓ Run compatibility check (dry-run, no writes)
   [INFO] BUILD SUCCESS
   ```

### 5c. Confirm it's green and no write occurred

- Green checkmark; **Merge** active (with branch protection).
- Optional **no-write proof** — the version count is unchanged before/after the check:
  ```bash
  curl -sf http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/versions
  ```

Merge it. **Schema Registration** then runs on the self-hosted runner and registers the new
version to the standing registry. Confirm via the Apicurio UI (<http://localhost:8888>) or the
`.../versions` call above.

**Expected check time:** seconds to ~1–2 min (Maven + dry-run only) — no container startup.

---

## 6. End-to-end test: incompatible schema change (blocked PR)

Proves the gate blocks a breaking change.

### 6a. Branch + incompatible edit

```bash
git checkout -b test/incompatible-change
```

In `order-contracts/src/main/resources/schemas/order-created.proto`, change field 1's type:

```proto
// BEFORE:  string order_id = 1;
// AFTER (INCOMPATIBLE — changes the wire type):
int64 order_id = 1;
```

```bash
git add order-contracts/src/main/resources/schemas/order-created.proto
git commit -m "test: incompatible change (should be blocked)"
git push origin test/incompatible-change
```

Open a PR against `main`.

### 6b. Watch the gate block the merge

1. **Compatibility check (orders + customers)** runs and **fails**:
   ```
   [ERROR] RuleViolationException: INCOMPATIBLE
   [ERROR] BUILD FAILURE
   ```
2. The PR Checks section shows a red X; **Merge** is greyed out (with branch protection).

### 6c. Fix and re-run

Revert field 1 back to `string`, commit, and push — GitHub re-runs the check automatically and it
turns green.

---

## 7. Run the bootstrap workflow

Attaches the `BACKWARD` rule to both artifacts. The host cold-start step (README §2) already
does this after `docker compose up`; run this workflow if you need to (re)apply the rules explicitly.

1. **Actions → Schema Governance Bootstrap → Run workflow**
2. Leave **registry URL** blank to use the standing local registry (`http://localhost:8080`), or
   enter another URL.
3. **Run workflow.** Each step prints its HTTP status:
   ```
   orders.events/OrderCreated: BACKWARD rule attached (HTTP 200)
   events.customers/CustomerRegistered: BACKWARD rule attached (HTTP 200)
   ```
   HTTP 409 means the rule already existed — also success.

---

## 8. Cost & availability model

A self-hosted runner changes the tradeoffs versus GitHub-hosted runners:

| Aspect | With this setup |
|--------|-----------------|
| **GitHub Actions minutes** | **None consumed** — jobs run on your machine, not GitHub's. (Hosted-minute caps are irrelevant.) |
| **Repo visibility** | **Must be private** while a runner is registered (fork-PR RCE risk on public). |
| **Availability** | Jobs run only when the machine, Docker Desktop, and the runner service are up. If the machine is off, jobs **queue** until it returns — they don't fail. |
| **Speed** | Fast — no Apicurio/Postgres startup per job; `~/.m2` and the registry are already warm. |
| **Infra** | One standing registry (`docker compose`) shared by local dev and CI. |
| **Scaling** | Move the runner + registry to an always-on VM (same `apicurio-local` label, same workflows) when a laptop isn't enough. |

---

## 9. Troubleshooting

### Workflow doesn't trigger on my PR
**Cause:** the PR doesn't touch `order-contracts/**` or `customer-contracts/**` (the `paths:`
filter). Changing only `docs/` correctly skips it. Touch a contract file to force a run.

### Job stays queued / "Waiting for a runner"
**Cause:** the self-hosted runner is offline. **Fix:** on the runner machine,
`cd ~/actions-runner && ./svc.sh status` (start it with `./svc.sh start`); confirm **Idle** in
Settings → Actions → Runners.

### Check fails with connection refused / can't reach `localhost:8080`
**Cause:** the standing registry isn't running. **Fix:** `docker compose up -d`; wait for
`apicurio` healthy; re-run the job.

### `INCOMPATIBLE` on the very first run, or "artifact not found"
**Cause:** the registry has no baseline because the host cold-start step (README §2) didn't seed
it. **Fix:** re-run `./mvnw … apicurio-registry:register` against the registry and confirm the two
`.../rules` curls return the BACKWARD rule.

### Compile fails with a Java/toolchain error on the runner
**Cause:** the runner machine lacks JDK 25 or `~/.m2/toolchains.xml`. **Fix:** ensure a JDK-25
entry in `~/.m2/toolchains.xml` (the compile uses `<release>25</release>` even though the Maven
runtime is Java 21).

### Maven wrapper permission denied
```
Permission denied: ./mvnw
```
**Fix:**
```bash
git update-index --chmod=+x mvnw
git commit -m "fix: make mvnw executable"
git push
```

### The required status check doesn't appear in branch protection
**Cause:** the check has never run, or you're searching for the old per-domain names. **Fix:**
open one PR so the job runs once, then search for **`Compatibility check (orders + customers)`**
(the matrix was collapsed into a single check).

---

## Quick command reference

```bash
# Push to GitHub (private repo)
git remote add origin https://github.com/anarefin/schema-registry-demo.git
git push -u origin main
git push origin feature/github-actions-schema-governance

# Activate workflows on main
git checkout main && git merge feature/github-actions-schema-governance && git push origin main

# Standing registry (on the runner machine)
docker compose up -d
curl -sf http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules

# Self-hosted runner service (on the runner machine)
cd ~/actions-runner && ./svc.sh status   # start/stop with ./svc.sh start|stop

# Create a test PR (compatible change)
git checkout -b test/my-schema-change
# ... edit a .proto or .json schema file ...
git add . && git commit -m "test: compatible schema change"
git push origin test/my-schema-change   # → open PR → check runs on the self-hosted runner

# Check mvnw is executable
git ls-files --stage mvnw | grep "^100755"  # prints a line if executable
```
