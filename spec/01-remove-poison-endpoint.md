# 01 — Remove/guard the poison demo endpoint

**Severity:** High · **Finding:** QUAL-001 · **Type:** AFK

## What to build

`POST /api/orders/poison` in the producer-service `OrderController` publishes garbage bytes
directly to the orders exchange (bypassing the converter, with valid schema headers) to force
consumer failures onto the DLQ. It is protected only by a Javadoc comment — no profile guard, no
auth — so in production it is an unauthenticated DLQ-flooding / abuse vector reachable by anyone
who can hit the service.

Remove it from the production controller, or gate it so it is never registered outside a demo
profile, and relocate the poison flow to an integration test where it belongs.

## Acceptance criteria

- [ ] `publishPoison` / `POST /api/orders/poison` is not registered under the default/`prod` profile.
- [ ] If retained for demos, it is gated behind `@Profile("demo")` (or `@ConditionalOnProperty`).
- [ ] The poison→DLQ behavior is exercised by an `*IT.java` integration test (Failsafe side).
- [ ] Existing DLQ-demo documentation (README/TESTING-GUIDE) is updated to reflect the new trigger path.

## Blocked by

None — can start immediately.
