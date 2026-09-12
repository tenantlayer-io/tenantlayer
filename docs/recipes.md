# Recipes

Complete, working answers to the things people actually build. Each one is the whole
solution — not a fragment — and each says what breaks if you get it wrong.

## Onboard a new tenant

Creating a tenant is three things: a registry row, their schema if the strategy needs one,
and whatever seed data your product requires.

```java
@Service
public class TenantOnboarding {

    private final TenantRegistry registry;
    private final TenantMigrationRunner migrations;
    private final TenantTasks tenants;

    public TenantOnboarding(TenantRegistry registry,
                            TenantMigrationRunner migrations,
                            TenantTasks tenants) {
        this.registry = registry;
        this.migrations = migrations;
        this.tenants = tenants;
    }

    @Transactional
    public void onboard(String tenantId, String plan) {
        // 1. The tenant exists from here on. forEachTenant will include it.
        registry.save(new TenantRegistration(
                tenantId, TenantStatus.ACTIVE, null, plan, null, Map.of("plan", plan)));

        // 2. Under schema- or database-per-tenant this creates and migrates their store.
        //    Under row-level security it is a no-op, so the call is safe either way.
        migrations.migrate(tenantId);

        // 3. Seed data, written as that tenant so the rows are stamped correctly.
        tenants.runAs(tenantId, () -> {
            settings.save(Settings.defaults());
            categories.saveAll(Category.starterSet());
        });
    }
}
```

The `runAs` in step three matters. Without it the seed rows are written with no tenant
bound: under row-level security the insert fails the policy, and under database-per-tenant
there is no connection to write them on. Either way onboarding half-succeeds.

## Suspend a tenant, and mean it

```java
@Service
public class TenantSuspension {

    private final TenantRegistry registry;
    private final TenantCacheEvictor evictor;

    @Transactional
    public void suspend(String tenantId) {
        TenantRegistration current = registry.find(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("no such tenant: " + tenantId));

        registry.save(new TenantRegistration(
                current.tenantId(), TenantStatus.SUSPENDED, current.region(),
                current.group(), current.datasourceRef(), current.metadata()));

        // Without this, their data stays readable from cache until entries expire.
        evictor.evictTenant(tenantId);
    }
}
```

Suspension takes effect on the next `forEachTenant` run and, for requests, within the
status cache TTL — thirty seconds by default, `0s` for instant. The filter refuses the
tenant with a 403 before any connection is bound, and iteration skips it; they apply the same
rule, the filter just remembers its answer for a moment. Nothing to opt into: the check is
on whenever a `TenantRegistry` bean exists (`tenantlayer.registry.enforce-status=false`
turns it off), and the tenant must be in the registry for it to apply. A tenant the registry
has never heard of is not refused; existence is a separate question from status.

## A nightly job across every tenant

```java
@Component
public class NightlyInvoicing {

    private static final Logger log = LoggerFactory.getLogger(NightlyInvoicing.class);

    private final TenantTasks tenants;
    private final InvoiceService invoices;

    @Scheduled(cron = "0 0 2 * * *")
    public void run() {
        try {
            tenants.forEachTenant(tenantId -> invoices.issueDue());
        } catch (TenantIterationException e) {
            // Every tenant was attempted. These are the ones that failed, by name.
            e.failures().forEach((tenantId, cause) ->
                    log.error("invoicing failed for {}", tenantId, cause));
            alerts.raise("nightly invoicing", e.failures().keySet());
        }
    }
}
```

`invoices.issueDue()` takes no tenant parameter and its queries have no tenant predicate —
each iteration runs with that tenant bound. Note what is missing: no `findAllTenants()`, no
loop variable threaded through three layers, and no way for one tenant's failure to skip
the rest of the night's work.

## Serve an admin endpoint that spans tenants

Support tooling needs to read across tenants, which is exactly what the rest of this library
prevents. Do it deliberately and narrowly:

```java
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SUPPORT')")
public class AdminController {

    private final TenantTasks tenants;
    private final OrderRepository orders;

    @GetMapping("/order-counts")
    public Map<String, Long> countsPerTenant() {
        // One bound scope per tenant, in turn — never a query with the policy disabled.
        return tenants.mapEachTenant(tenantId -> orders.count());
    }
}
```

This iterates tenants rather than bypassing isolation. Reading across tenants on an
unscoped connection would work too, and would mean one bug in this controller could return
everything to anyone.

## Call another service and keep the tenant

```java
@Configuration
class Clients {

    // The interceptor is added by autoconfiguration — the builder is all you need.
    @Bean
    RestClient billingClient(RestClient.Builder builder) {
        return builder.baseUrl("https://billing.internal").build();
    }
}

@Service
class SubscriptionService {

    private final RestClient billing;

    Invoice latestInvoice() {
        // X-Tenant-ID is attached from the bound tenant. Nothing here mentions it.
        return billing.get().uri("/invoices/latest").retrieve().body(Invoice.class);
    }
}
```

On the receiving side, treat that header as client-supplied — because from its point of
view it is. A service reachable beyond your trust boundary should resolve from a token and
verify membership; see [securing resolution](securing-resolution.md).

## Process a Kafka topic per tenant

```java
@Component
public class OrderEvents {

    // Produced with the tenant written into a record header, automatically.
    public void publish(Order order) {
        kafka.send("orders", order.getId().toString(), order);
    }

    // Consumed with that tenant bound before the method body runs.
    @KafkaListener(topics = "orders")
    public void handle(Order order) {
        fulfilment.schedule(order);
    }

    // A batch can span tenants, so there is no single tenant to bind for the batch.
    @KafkaListener(topics = "orders", batch = "true")
    public void handleBatch(List<ConsumerRecord<String, Order>> records) {
        for (ConsumerRecord<String, Order> record : records) {
            TenantKafka.runAsRecordTenant(record, () -> fulfilment.schedule(record.value()));
        }
    }
}
```

A record with no tenant header is refused rather than processed as whoever came before it.
On a long-lived consumer thread the dangerous failure is not a lost tenant but a **retained**
one, and that is a cross-tenant write rather than an empty read.

## Report generation on a background thread

```java
@Service
public class ReportService {

    // @Async is decorated automatically, including under virtual threads.
    @Async
    public CompletableFuture<Report> generate(String month) {
        return CompletableFuture.completedFuture(builder.build(month));
    }

    // A raw CompletableFuture is not — the common ForkJoinPool is not Spring's to decorate.
    public CompletableFuture<Report> generateDirectly(String month) {
        return CompletableFuture.supplyAsync(
                TenantExecutors.supplier(() -> builder.build(month)));
    }

    // Or wrap an executor once and stop thinking about it.
    private final Executor pool = TenantExecutors.wrap(Executors.newFixedThreadPool(4));

    public CompletableFuture<Report> generateOnOwnPool(String month) {
        return CompletableFuture.supplyAsync(() -> builder.build(month), pool);
    }
}
```

Get this wrong and nothing throws: the report is built with no tenant, every query returns
nothing, and you ship an empty PDF.

## Migrate every tenant on deploy

```java
@Component
public class MigrateOnStartup implements ApplicationRunner {

    private final TenantMigrationRunner migrations;

    @Override
    public void run(ApplicationArguments args) {
        MigrationOutcome outcome = migrations.migrateAll();
        log.info("migrated {} tenants", outcome.migrated().size());
    }
}
```

```properties
# Boot auto-configures Flyway from the classpath alone, and would run it once against
# the default schema before this ever executes.
spring.flyway.enabled=false
tenantlayer.migration.locations=classpath:db/tenant-migration
```

`migrateAll()` runs once for a shared schema and once per tenant for schema- or
database-per-tenant — it asks the strategy rather than assuming. Failures are collected and
named rather than aborting on the first tenant. See [migrations](migrations.md).

## Prove any of this in a test

```java
@Test
@WithTenant("acme")
void theNightlyJobDoesNotLeakAcrossTenants() {
    invoices.issueDue();

    assertThat(invoiceRepository.findAll()).isNotEmpty();
    assertTenantCannotSee("globex");
}
```

Then break it. Comment out the `runAs`, drop the policy, or add the cache to
`tenantlayer.cache.shared`, and confirm the test goes red. A test that passes with the
mechanism removed was never testing the mechanism — see [testing](testing.md).

---

Every recipe here is exercised by
[`examples/order-service`](https://github.com/tenantlayer-io/tenantlayer/tree/main/examples/order-service),
a complete Spring Boot application whose own code contains no tenancy logic at all.
