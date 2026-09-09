# Metrics

Every observation your application records is tagged with the acting tenant, so
`http.server.requests` can be broken down by who caused the traffic.

```
http_server_requests_seconds_count{uri="/orders", tenant="acme"}    1423
http_server_requests_seconds_count{uri="/orders", tenant="globex"}   118
http_server_requests_seconds_count{uri="/orders", tenant="__none__"}   2
```

On by default when Micrometer is on the classpath:

```properties
tenantlayer.metrics.enabled=true
tenantlayer.metrics.max-tenants=100
tenantlayer.metrics.overflow-value=__other__
```

## The cap is the feature

A tenant tag is the obvious thing to add and the easy way to take a monitoring system down.
Every distinct tag value is another time series, so an unbounded tenant tag on a busy
endpoint multiplies your series count by your tenant count. With ten thousand tenants the
first anyone hears of it is a Prometheus that will not start, or an invoice.

So a fixed number of tenants get a series of their own, and everyone after that shares one:

| Tenants seen | Tagged as |
|---|---|
| First 100 (`max-tenants`) | their own id |
| Everyone after | `__other__` |
| Requests with no tenant | `__none__` |

**Which tenants get their own series is simply whoever arrived first.** Any cleverer policy
— busiest, most recent, largest — needs state that is itself unbounded, which would make the
thing being capped the thing doing the capping.

### Reaching the cap is not silent

```
WARN  metric tenant tag cap of 100 reached; further tenants are reported as '__other__'.
      Raise tenantlayer.metrics.max-tenants, or leave it — the cap is what keeps the number
      of time series bounded.
```

Logged once. Silently folding tenants into a bucket would look exactly like those tenants
sending no traffic, which is the kind of monitoring gap only ever discovered mid-incident.

### Choosing a cap

The right number is *"how many tenants do you want to see individually on a dashboard"*,
which is usually smaller than your tenant count. A hundred rows is already more than anyone
reads.

If you genuinely need per-tenant figures for thousands of tenants, a metrics system is the
wrong place for it — that is a reporting query against your own data, not a time series per
customer.

## `__none__` and `__other__` are different on purpose

They answer different questions, and collapsing them would hide both:

- **`__none__`** — a request that resolved no tenant. Under strict mode these are rejected,
  so a rising `__none__` count means something is calling you wrongly, or an unscoped path
  is busier than you thought.
- **`__other__`** — a tenant that exists but is past the cap. Rising traffic here is normal
  growth, and tells you the cap needs raising if you care about those tenants individually.

## Querying it

```promql
# Requests per second by tenant, top 10
topk(10, sum by (tenant) (rate(http_server_requests_seconds_count[5m])))

# Is any single tenant driving your error rate?
sum by (tenant) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# Traffic arriving with no tenant at all
rate(http_server_requests_seconds_count{tenant="__none__"}[5m])
```

## Why an ObservationFilter and not a MeterFilter

The tenant must be read *when the observation happens*, on the request thread. A
`MeterFilter` runs once per meter, when it is first registered — it would capture whichever
tenant happened to make the first request to that endpoint and tag every subsequent request
with it, which is worse than having no tag at all.

## Turning it off

```properties
tenantlayer.metrics.enabled=false
```

The tag also disappears if Micrometer is not on the classpath, which is the normal state of
a service with no actuator. Nothing fails; the autoconfiguration simply does not apply.

## For a multi-service estate

This is the answer for a company running twenty services. Each one tags its own metrics with
the tenant, and the aggregating is done by the Prometheus and Grafana you already run —
rather than by visiting twenty dashboards.

See [observability in context propagation](context-propagation.md) for the MDC tenant on log
lines, which is the same idea for logs.
