# Getting started

## 1. Add the dependency

```xml
<dependency>
  <groupId>io.tenantlayer</groupId>
  <artifactId>tenantlayer-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Java 17+, Spring Boot 3.3+, Postgres. Hibernate 6 and 7 are both supported.

## 2. Configure resolution

```properties
tenantlayer.header=X-Tenant-ID
tenantlayer.strict=true
```

`strict=true` is the default and should stay that way. A request with no resolvable tenant
is rejected with 400 rather than served with no tenant bound — which would return an empty
result set that reads as "no data" and sends the caller hunting for a bug in their query.

> **Read [Securing resolution](securing-resolution.md) before exposing this to the
> internet.** A header is whatever the caller typed. It is safe behind a gateway that
> overwrites it, and unsafe otherwise.

## 3. Add the policy to your tables

Generate it rather than writing it by hand:

```java
@Autowired TenantScopedEntityScanner scanner;
@Autowired RlsPolicyGenerator generator;

System.out.println(generator.generate(scanner.scan()));
```

Review the output, commit it as a migration, apply it with Flyway or Liquibase. It is plain
SQL — the policies keep working if you remove TenantLayer.

## 4. Connect as a role that is not the owner

This is the step people skip, and skipping it silently disables everything above.

```sql
create role orders_app login password '...';
grant usage on schema public to orders_app;
grant select, insert, update, delete on orders to orders_app;
```

A Postgres **superuser bypasses row-level security entirely**, and so does the table owner
unless the table has `FORCE ROW LEVEL SECURITY` (the generator emits it). Connect as a
least-privileged role, or your policies are decorative.

## 5. Write nothing else

```java
@RestController
@RequestMapping("/orders")
class OrderController {

    private final OrderRepository orders;

    @GetMapping
    List<Order> list() {
        return orders.findAll();   // no tenant parameter, no filter, no predicate
    }
}
```

`findAll()` emits `select ... from orders` with no `where tenant_id = ?`. The rows come
back scoped because Postgres applies the policy to the connection.

## 6. Prove it

```java
@Test
@WithTenant("acme")
void oneTenantCannotSeeAnother() {
    assertThat(orders.findAll()).isNotEmpty();   // acme can see its own
    assertTenantCannotSee("globex");             // and nothing of globex's
}
```

Both halves matter. "Cannot see the other tenant" passes trivially against an empty table;
`assertTenantCannotSee` refuses to run unless the other tenant genuinely has rows. See
[Testing](testing.md).
