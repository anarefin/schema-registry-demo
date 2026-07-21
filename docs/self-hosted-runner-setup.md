# Self-hosted runner setup (Apicurio-local)

Schema **compat-check**, **register**, and **governance-bootstrap** workflows run on a self-hosted
GitHub Actions runner labelled `apicurio-local`. That runner shares a host with a standing
Apicurio Registry (and its Postgres) so CI can validate against real registered history without
spinning an ephemeral registry per job.

Unit tests and offline schema **drift** do **not** need this runner — they use `ubuntu-latest`
(see `.github/workflows/build-and-test.yml` and `schema-drift-check.yml`).

## Host prerequisites

1. **Docker Compose** stack from this repo reachable on the runner host:
   ```bash
   docker compose up -d postgres apicurio apicurio-ui
   ```
   Wait until Apicurio is healthy (`http://localhost:8080`).
2. **Seed schemas + FORWARD rules** once (cold start), same as [README §2](../README.md):
   ```bash
   ./gradlew registerSchemas \
     -Papicurio.registry.url=http://localhost:8080
   # then attach FORWARD rules (bootstrap workflow or README curl loop)
   ```
   Or run the **Schema Governance Bootstrap** workflow from the Actions UI.
3. **JDK 25** installed; `JAVA_HOME` points at it (Gradle toolchain uses this JDK).

## Install the runner

Follow GitHub’s [self-hosted runner](https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/adding-self-hosted-runners)
docs for this repository (or org). When configuring labels, include:

- `self-hosted` (default)
- `apicurio-local` (required by the schema workflows)

Example:

```bash
./config.sh --url https://github.com/<org>/<repo> --token <token> --labels apicurio-local
./run.sh   # or install as a service
```

## Verify

1. Runner appears under repo **Settings → Actions → Runners** with label `apicurio-local`.
2. `curl -sf http://localhost:8080/health/ready` (or Apicurio’s health endpoint) succeeds on the host.
3. Manually dispatch **Schema Governance Bootstrap**, or open a PR that touches `*-contracts/**`
   and confirm **Schema Compatibility Check** picks the self-hosted runner.

## Notes

- Compat-check uses `dryRun=true` (no writes). Register and bootstrap write to the standing registry.
- Keep Postgres volume durable (`pgdata` in `docker-compose.yml`) so compat history survives restarts.
- Do not point production secrets at this POC registry; it is a shared CI/dev governance store.
