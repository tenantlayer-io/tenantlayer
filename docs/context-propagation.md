# Context propagation

The tenant is resolved once, on the request thread. Everything that happens afterwards on
another thread, or on another machine, has to be told.

When propagation fails, nothing throws. The work continues with no tenant bound, the
connection is published with the empty tenant, and queries return zero rows. It surfaces as
missing data, not as an error — which is why each of these is a feature rather than a note
in the README.

## What is handled automatically

| Boundary | How |
|---|---|
| `@Async` and Spring task executors | Task decorator, registered by autoconfiguration |
| Virtual threads (`spring.threads.virtual.enabled`) | A second decorator for the executor Boot builds instead |
| Servlet requests | `TenantFilter`, with guaranteed unwind |
| Outbound `RestTemplate` / `RestClient` | Request interceptor |
| Outbound `WebClient` | Exchange filter |
| Outbound Feign | Request interceptor |
| Kafka produce | Producer interceptor, tenant written to a record header |
| Kafka consume | Record interceptor, tenant restored before the listener |

### Virtual threads deserve a note

Setting `spring.threads.virtual.enabled=true` makes Boot build a `SimpleAsyncTaskExecutor`
instead of a `ThreadPoolTaskExecutor`. A decorator registered only for the pooled one
disappears along with it — so one property, widely recommended, with no mention of tenancy
anywhere near it, would otherwise remove tenant propagation from every `@Async` method.
TenantLayer registers both.

## What you have to ask for

### CompletableFuture without a Spring executor

`CompletableFuture.supplyAsync(...)` with no executor runs on the common ForkJoinPool,
which Spring has never heard of and cannot decorate.

```java
CompletableFuture.supplyAsync(TenantExecutors.supplier(() -> reports.build()));
```

Or wrap the executor once and forget about it:

```java
Executor tenantAware = TenantExecutors.wrap(myExecutor);
CompletableFuture.supplyAsync(() -> reports.build(), tenantAware);
```

`TenantExecutors.wrap` also accepts an `ExecutorService`, covering `submit`, `invokeAll`
and `invokeAny`.

Three names — `runnable`, `callable`, `supplier` — rather than three overloads of one,
because a method reference like `this::loadReport` satisfies both `Callable` and `Supplier`
and an overloaded `capture(...)` is ambiguous at exactly the call sites people write.

### Scheduled jobs

A `@Scheduled` method runs on a scheduler thread no filter ever touched, so there is no
tenant. See [The tenant registry](tenant-registry.md) for `forEachTenant`.

### Kafka batch listeners

A batch can span tenants, so there is no single tenant to bind — and binding the first
record's would be worse than binding none. Scope each record:

```java
@KafkaListener(topics = "orders", batch = "true")
void handle(List<ConsumerRecord<String, Order>> records) {
    for (var record : records) {
        TenantKafka.runAsRecordTenant(record, () -> service.handle(record.value()));
    }
}
```

A record carrying no tenant header is refused rather than processed as whoever came before
it — on a long-lived listener thread the dangerous failure is not a lost tenant but a
retained one, and that is a cross-tenant write.

## A note on outbound headers

Outbound propagation is context propagation, not authentication. The receiving service must
treat the header as client-supplied — because from its point of view, it is. A service
exposed beyond your trust boundary should resolve from tokens and verify membership; see
[Securing resolution](securing-resolution.md).

A call made with no tenant bound sends no header at all, rather than an empty one. An empty
value is a claim about the tenant; its absence is not.
