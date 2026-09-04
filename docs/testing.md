# Testing

## The assertion

```java
@Test
@WithTenant("acme")
void oneTenantCannotSeeAnother() {
    assertThat(orders.findAll()).isNotEmpty();
    assertTenantCannotSee("globex");
}
```

`assertTenantCannotSee` is deliberately two-sided. Proving "tenant A sees no rows belonging
to B" is worthless alone — an empty table passes it. So it first checks, on a privileged
connection that bypasses the policy, that B's rows genuinely exist, and fails with a message
telling you to seed them if they do not. It also refuses to run with no tenant bound, and
refuses when the acting tenant *is* the tenant you asked about.

## The fixture

```java
static final TenantPostgres POSTGRES = TenantPostgres.start()
        .withTenantTable("orders", "item varchar(255) not null")
        .withRegistry("acme", "globex");

@BeforeEach
void bind() {
    POSTGRES.seedRow("orders", "acme",   Map.of("item", "laptop"));
    POSTGRES.seedRow("orders", "globex", Map.of("item", "monitor"));
    IsolationAssertions.bind(POSTGRES.applicationDataSource(), POSTGRES.privilegedDataSource());
    IsolationAssertions.bindTable("orders", "tenant_id");
}
```

`withTenantTable` creates the table with the index, `FORCE ROW LEVEL SECURITY` and a
correctly guarded policy. `withUnprotectedTable` creates one without a policy, for testing
the discriminator strategy on its own.

### Two DataSources, and they are not interchangeable

- `applicationDataSource()` — a least-privileged role, neither superuser nor owner. This is
  what the code under test uses, and it is subject to the policy.
- `privilegedDataSource()` — bypasses the policy. For seeding and for proving another
  tenant's rows exist. Never the subject of an assertion.

Standing up a Postgres container is one line. Standing up one that can *prove* isolation is
not, and the trap is the connection you test through: Testcontainers hands you a superuser,
superusers bypass RLS outright, and a suite written against that connection passes whether
or not the policy works — including after someone deletes it.

The pool defaults to one connection, so a tenant left behind on a recycled connection is
observed deterministically rather than occasionally.

## Break it and watch it fail

Green tests are not evidence. After a test passes, break the implementation and confirm the
test goes red. Every isolation claim in this library was checked that way, and it caught
something real every time — including a `BUILD SUCCESS` with zero tests executed, an
`@Async` test that would have passed if the work never left the calling thread, and an
`assertTenantCannotSee` that passed with the library switched off entirely.

The cheapest version of this for your own code:

```
mvn test -Dspring.autoconfigure.exclude=io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration
```

If your isolation tests still pass with TenantLayer turned off, they are not testing
isolation.
