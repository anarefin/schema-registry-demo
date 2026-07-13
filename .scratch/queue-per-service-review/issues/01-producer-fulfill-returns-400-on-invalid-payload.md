# 01 — Producer fulfill returns 400 on invalid payload

**What to build:** `POST /api/orders/fulfill` with a body missing nested fields (`buyer`, `shipping`, or `payment`) must follow the same validation contract as every other order endpoint: schema validation is the single authority, a violation returns HTTP 400, and no message is emitted to RabbitMQ. Today a missing nested object causes a null dereference and an unhandled 500.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `POST /api/orders/fulfill` with a body omitting `buyer` returns 400, not 500
- [ ] Same for bodies omitting `shipping` or `payment`
- [ ] No AMQP message is published on any validation failure
- [ ] Existing producer validation tests still pass; add coverage for the fulfill endpoint's incomplete-payload cases
