# Onboarding a tenant

Creating a registry row is not the same as a usable tenant. Onboarding is the whole
sequence, and the order matters:

```
registry row, PROVISIONING   the tenant exists; nothing serves it yet
migrate                      its schema or database, when the strategy needs one
hooks                        seed data, and everything else application-specific
registry row, ACTIVE         now it is served
```

## Calling it

From your own signup path. This is a runtime call — no deployment, no restart:

```java
@RestController
class SignUpController {

    private final TenantProvisioning provisioning;
    private final AccountService accounts;

    @PostMapping("/signup")
    Account signUp(@RequestBody SignUpRequest request) {
        Account account = accounts.create(request);
        provisioning.onboard(account.slug());
        return account;
    }
}
```

With more than an id:

```java
provisioning.onboard(new TenantRegistration(
        "globex", TenantStatus.ACTIVE, "eu-west-1", "enterprise",
        "shard-a",                       // which database, under DATABASE_PER_TENANT
        Map.of("plan", "enterprise")));
```

## Hooks: everything a tenant needs beyond a row

```java
@Component
class SeedStarterData implements TenantProvisioningHook {

    private final SettingsRepository settings;
    private final CategoryRepository categories;

    @Override
    public void onTenantCreated(String tenantId) {
        // No tenant parameter. The new tenant is bound while this runs.
        settings.save(Settings.defaults());
        categories.saveAll(Category.starterSet());
    }

    @Override
    public int order() {
        return 10;   // lower runs first
    }
}
```

**The tenant is bound while a hook runs**, which is the whole reason this exists rather than
being left to the caller. Rows written with no tenant bound fail the policy under row-level
security and have no connection at all under database-per-tenant — and writing that
orchestration by hand is where people get it wrong.

Anything can be a hook: a Stripe customer, a search index, a default workspace, a warmed
cache.

## When something fails

A hook that throws stops provisioning, and the tenant is left `PROVISIONING`:

```
TenantProvisioningException: provisioning tenant 'acme' failed at hook SeedStarterData;
it is left PROVISIONING and will not be served. Fix the cause and onboard it again —
onboarding is idempotent.
```

That state is deliberate. A half-created tenant that is simply **absent** looks like one
nobody asked for; one left **ACTIVE** looks ready and is not. `PROVISIONING` is neither: it
is not served, `forEachTenant` skips it, and the row says what happened.

Retrying is calling `onboard` again — **so a hook must be safe to run twice**, because it
will be.

## Idempotency

| State | `onboard` does |
|---|---|
| Tenant is `ACTIVE` | nothing at all |
| Tenant is `PROVISIONING` | starts again from the beginning |
| Tenant does not exist | the full sequence |

A retried webhook, a redelivered message, and an operator running it twice are all safe.

## Migrations

Under **row-level security** a new tenant needs no migration: the tables are shared and
already there. `onboard` checks `migratesPerTenant()` and skips Flyway entirely rather than
starting it up to discover there is nothing to apply.

Under **schema-per-tenant** or **database-per-tenant** it runs that tenant's migrations
before any hook, so a hook writing seed data has somewhere to write it.

## Twenty services

`onboard` provisions **this service**. It cannot reach the other nineteen, and an API that
pretended otherwise would be lying.

In practice one service owns tenant lifecycle — signup, suspension, the admin screens — and
writes the registry row. What the rest do depends entirely on your strategy:

- **Row-level security** — nothing. The tables are shared, and the policy covers the new
  tenant the moment a row carries its id. Nineteen services have no work to do.
- **Schema- or database-per-tenant** — each service owning tenant data provisions its own
  store, by consuming a *tenant created* event and calling `onboard`, or by picking it up at
  its next deployment through `migrateAll()`.

That split is the reason this is a primitive rather than an orchestrator. See
[the tenant registry](tenant-registry.md) and [migrations](migrations.md).

## Testing it

```java
@Test
void anOnboardedTenantIsUsableImmediately() {
    provisioning.onboard("newcorp");

    assertThat(registry.find("newcorp").orElseThrow().status()).isEqualTo(ACTIVE);
    assertThat(get("/orders", "newcorp").getBody()).contains("Starter plan");
    assertThat(get("/orders", "acme").getBody())
            .as("the new tenant's seed data leaked")
            .doesNotContain("Starter plan");
}
```

Then break it: stop binding the tenant during hooks and confirm this fails. Under row-level
security the seeding insert is rejected by the policy, so it fails loudly — which is the
test doing its job.
