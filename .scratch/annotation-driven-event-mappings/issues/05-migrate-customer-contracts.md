# 05 — Migrate customer-contracts (second domain E2E)

**What to build:** Same cutover for the customer domain after orders is green. Three customer event
records annotated; `CustomerTypeMappingAutoConfiguration` thinned to package-scoped registrar
import for `com.example.contracts.customers`; manual beans gone; packaged index present. Both
domains produce the seven expected mappings. `schema-messaging-core` unchanged and still free of
contracts deps; architecture coverage proves indexed registration declares no exchanges and
preserves `core ↛ contracts`. Full focused module suite + package verify green.

**Blocked by:** 04 — Migrate order-contracts (first domain E2E)

**Status:** ready-for-agent

- [ ] Three customer records annotated; no manual per-event `TypeMapping` bean methods remain.
- [ ] Auto-config imports the indexed registrar scoped to the exact customers event package.
- [ ] Packaged `customer-contracts` jar contains `META-INF/event-mappings.idx` with the three FQCNs.
- [ ] All seven events produce expected coordinates, routing, exchanges, and bean names.
- [ ] Core needs no mapping-discovery change; `core ↛ contracts` and no-exchange-from-mappings hold.
- [ ] `./mvnw -pl schema-gen-tools,event-contract-kit,order-contracts,customer-contracts,schema-messaging-core -am test`
      and `./mvnw -pl order-contracts,customer-contracts -am package -DskipTests` pass.
