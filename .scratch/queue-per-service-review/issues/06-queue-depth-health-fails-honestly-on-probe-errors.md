# 06 — Queue depth health fails honestly on probe errors

**What to build:** When the queue depth health check cannot query RabbitMQ for a handled event's main queue or DLQ (transient channel error, queue not yet declared, broker unreachable), `/actuator/health` must not report UP with a fake depth of zero. A probe failure must surface as DOWN or UNKNOWN so alerting can distinguish a real outage from a genuinely empty DLQ.

**Blocked by:** 05 — Handled event types discovered once at startup

**Status:** ready-for-agent

- [ ] A failed `getQueueInfo` call does not return depth `0` and does not leave `dlqEmpty` true
- [ ] Health status reflects the failure (DOWN or UNKNOWN with error detail), not UP
- [ ] A genuinely empty DLQ still reports UP
- [ ] A DLQ with messages still reports DOWN
- [ ] Test covers the error-swallowing scenario that currently masks outages
