# Minimal POC — step-by-step guide

A copy-paste runbook that takes you from a clean checkout to having exercised all three core
concepts of this POC on **real Apicurio + RabbitMQ**:

1. **Schema-governed flow** — produce → validate against the registry schema → publish to
   RabbitMQ → consume → validate → deserialize.
2. **Schema governance** — the registry rejects an incompatible schema at registration time.
3. **Failure model** — permanent failures go straight to the DLQ; transient failures are
   retried (one 5s tier, up to 3 attempts) before the DLQ.

The system is one message type (`OrderCreated`) flowing **producer-service (:8081)** →
Apicurio + RabbitMQ → **consumer-service (:8082)**.

Every command below is runnable as-is. Seed payloads are written inline.

---

## 1. Prerequisites

| Tool | Version |
|---|---|
| JDK | 25 — needs an entry in `~/.m2/toolchains.xml` (vendor `oracle`, `id` `25-oracle`) |
| Docker + Compose | Compose v2 (`docker compose`) |
| Maven | Use the committed wrapper `./mvnw` — never a system `mvn` |

---

## 2. Build

```bash
./mvnw clean install -DskipTests
```

Builds the 4 modules (`schema-messaging-core`, `order-contracts`, `producer-service`,
`consumer-service`) and runs jsonschema2pojo to generate the `OrderCreated` POJO. (Run the
tests later with `./mvnw verify`, which needs Docker for the Testcontainers ITs.)

---

## 3. Start infrastructure

```bash
docker compose up
```

Wait until every container is healthy. URLs:

| Service | URL | Credentials |
|---|---|---|
| Apicurio Registry API | http://localhost:8080 | none |
| Apicurio Registry UI | http://localhost:8888 | none |
| RabbitMQ management UI | http://localhost:15672 | guest / guest |

Schema registration is **not** a compose service — it is a host-Maven step (next).

---

## 4. Register the schema — rule first, then register

Registration through the Maven plugin **is** the governance gate: the registry validates the
schema against the attached compatibility rule and the goal fails if it is incompatible. So we
attach the rule **before** the registration it should govern, and dry-run a compatibility check
before the real write.

One ordering constraint: an Apicurio **artifact-level** rule can only be attached to an artifact
that already exists. So the baseline (v1) is registered first purely to create the artifact; the
rule then governs every change after it.

```bash
# 1. Register the baseline (v1, required fields only) — creates the OrderCreated artifact.
./mvnw -pl order-contracts verify -Pbaseline \
       -Dapicurio.registry.url=http://localhost:8080

# 2. Attach the FORWARD compatibility rule to the artifact (register does not do this).
#    JSON Schema property additions are classified NARROWED — rejected under BACKWARD,
#    accepted under FORWARD, which is why this artifact uses FORWARD.
curl -s -o /dev/null -w "rule attach: %{http_code}\n" -X POST \
  "http://localhost:8080/apis/registry/v3/groups/events.orders/artifacts/OrderCreated/rules" \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"COMPATIBILITY","config":"FORWARD"}'

# 3. Dry-run pre-flight: check the current schema is FORWARD-compatible with the registered
#    baseline WITHOUT writing a version. (Meaningful now that the v1 baseline exists.)
./mvnw -pl order-contracts verify -Pcompat-check \
       -Dapicurio.registry.url=http://localhost:8080

# 4. Register the current schema for real — now governed by the FORWARD rule.
#    (This goal lists v1 + current; v1 is idempotently found, so it creates the current version.)
./mvnw -pl order-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080
```

Confirm in the Apicurio UI (http://localhost:8888) → group `events.orders` → `OrderCreated`:
you should see 2 versions (v1 baseline + current) and a COMPATIBILITY = FORWARD rule.

---

## 5. Run the services

Two terminals:

```bash
# Terminal 1 — producer on :8081
./mvnw -pl producer-service spring-boot:run
```

```bash
# Terminal 2 — consumer on :8082
./mvnw -pl consumer-service spring-boot:run
```

The consumer declares the topology on startup: `events.exchange`, `orders.created.queue`,
`events.dlx` + `orders.created.dlq`, and the single retry queue `orders.created.retry.5s`.

---

## 6. Flow — happy path (seed data)

```bash
curl -s -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-1","productId":"prod-42","quantity":2,"totalAmount":99.99,"currency":"USD"}'
```

Expected: HTTP `201` (no body). The **producer** validated the payload against the registry
schema, serialized it, and published it with `X-Schema-*` headers. The **consumer** log shows:

```
Received OrderCreated orderId=<uuid> customerId=cust-1 productId=prod-42 qty=2
```

That is the full governed loop: produce → validate → publish → consume → validate → deserialize.

---

## 7. Flow guardrail — schema violation (seed data)

Send a body missing a required field (`currency`). The producer validates before publishing.

```bash
curl -s -i -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-1","productId":"prod-42","quantity":2,"totalAmount":99.99}'
```

Expected: HTTP `400` with body `Schema validation failed: ...`. **No message is published** —
nothing reaches RabbitMQ, and the consumer logs nothing.

---

## 8. Failure model — poison message → DLQ

```bash
curl -s -i -X POST http://localhost:8081/api/orders/poison
```

Expected: HTTP `202`. This publishes garbage JSON bytes **directly** to the exchange (bypassing
the producer's validation) with valid `X-Schema-*` headers. The consumer cannot deserialize/
validate them → `SchemaValidationException` (a **permanent** failure) → routed straight to the
DLQ with no retry.

Verify in the RabbitMQ UI (http://localhost:15672, guest/guest):

- **Queues → `orders.created.dlq`** has 1 message.
- Click it → **Get Message(s)** → inspect the `X-Failure-*` headers (reason, message, stack
  trace truncated to 4 KB, original routing key, failed-at) and `X-Retry-Count = 0`.

---

## 9. Failure model — transient failure → retry → DLQ

A transient failure (registry unreachable, schema-not-found) is retried via the single 5s tier
up to 3 times, then dead-lettered. Force one by pointing the **consumer** at a dead registry so
it cannot resolve the schema:

```bash
# Stop the consumer (Ctrl-C in Terminal 2), then restart it pointing at an unreachable registry:
APICURIO_REGISTRY_URL=http://localhost:9999 ./mvnw -pl consumer-service spring-boot:run
```

```bash
# Producer's registry (:8080) is still up, so publishing still works:
curl -s -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust-9","productId":"prod-7","quantity":1,"totalAmount":12.50,"currency":"USD"}'
```

The consumer receives the message but cannot resolve the schema from `:9999`
(`RegistryUnavailableException`, classified **transient**). Watch the consumer logs: the message
cycles through `orders.created.retry.5s` and after 3 attempts (~15s) lands on
`orders.created.dlq` with `X-Failure-Retry-Count = 3`.

> This works because the consumer restarts with an **empty** schema cache, so there is nothing
> to serve. If the cache were already **warm** (the consumer had resolved this schema before the
> registry went down), the `SchemaResolver` would serve the last-known-good schema and the
> message would process normally — that is the serve-stale-on-outage resilience working as
> intended, not a bug.

Restore normal operation:

```bash
# Ctrl-C, then restart the consumer normally:
./mvnw -pl consumer-service spring-boot:run
```

---

## 10. Governance — compatible change accepted

Adding an **optional** property is FORWARD-compatible. Edit
`order-contracts/src/main/resources/schemas/order-created.json` and add a property, e.g. inside
`"properties"`:

```json
    "giftMessage": {
      "type": "string",
      "description": "Optional gift message (compatible addition)"
    }
```

Re-run the real registration — it succeeds and creates a new version:

```bash
./mvnw -pl order-contracts apicurio-registry:register \
       -Dapicurio.registry.url=http://localhost:8080
```

The Apicurio UI now shows an additional version of `OrderCreated`. (Revert the edit afterwards
if you want a clean tree.)

---

## 11. Governance — incompatible change rejected

Changing an existing property's type (`quantity` integer → string) is incompatible under every
level. The `incompatible-demo` profile registers exactly that schema and **must fail**:

```bash
./mvnw -pl order-contracts verify -Pincompatible-demo \
       -Dapicurio.registry.url=http://localhost:8080
```

Expected: **BUILD FAILURE** with an Apicurio compatibility-rejection message. The registry
refused the incompatible version — that rejection is the governance gate doing its job.

(The same `compat-check` dry-run used as the §4 step-3 pre-flight checks compatibility without
writing a version:
`./mvnw -pl order-contracts verify -Pcompat-check -Dapicurio.registry.url=http://localhost:8080`.)

---

## 12. Teardown

```bash
# Ctrl-C both services, then:
docker compose down
```

To also drop the registry's Postgres volume (so a re-run starts with no registered schemas):

```bash
docker compose down -v
```
