# Local demo — browse it in pgAdmin, drive it from Postman

Everything below is running right now.

## 1. pgAdmin

**http://localhost:5050** — running as the container `tl-pgadmin`.

| | |
|---|---|
| pgAdmin login | `admin@tenantlayer.io` / `admin` |
| Server | "TenantLayer demo (orders)" — already configured |
| Database password when prompted | `admin_pwd` |

The server entry is preloaded, so expand **Servers → TenantLayer demo (orders) → Databases
→ orders → Schemas → public → Tables → orders**.

Note the connection inside pgAdmin uses host `tl-orders-pg` port `5432`, not
`localhost:55433` — pgAdmin runs in its own container, where "localhost" is itself. Both
containers share the `tenantlayer-net` network so it can address Postgres by name.

**From your Mac** (DBeaver, psql, anything else) use `localhost:55433`, database `orders`,
user `admin`, password `admin_pwd`. `pgadmin-servers.json` is the importable version of
that host-side connection.

**Expect to see every tenant's rows when you connect.** `admin` is a superuser, and
superusers bypass row-level security completely. That is not the policy failing — it is
why the service connects as `orders_app` instead, a role that is neither superuser nor
table owner.

`demo-queries.sql` walks through the mechanism from the database side: read the policy,
`set role orders_app`, then switch tenants with `set_config` and watch the same `select`
return different rows. Verified output:

```
acme sees: 2
globex sees: 2
no tenant sees: 0
acme querying globex explicitly: 0     <- even a hand-written predicate cannot get around it
admin sees: 6                          <- superuser, bypasses RLS
```

## 2. Postman

Import `TenantLayer-OrderService.postman_collection.json`. Base URL is
`http://localhost:8080`.

Run the requests top to bottom — 5 and 6 use ids captured by 1 and 2. Each has assertions
attached, so the Postman test tab tells you pass or fail.

| # | Request | Expect |
|---|---|---|
| 1 | Place order as acme | 201, `tenantId` comes back `acme` although the service never sent one |
| 2 | Place order as globex | 201 |
| 3 | List as acme | only acme's rows |
| 4 | List as globex | only globex's rows |
| 5 | acme reads its own order | 200 |
| 6 | **acme reads globex's order by id** | **404** |
| 7 | No `X-Tenant-ID` header | **400**, not an empty list |
| 8 | Any tenant you like | change the `tenant` variable and re-send |

Request 6 is the one worth staring at. 404 rather than 403 — a 403 would confirm the row
exists, which is itself a leak.

## 3. Service control

    # logs
    tail -f /tmp/order-service.log

    # stop
    pkill -f "spring-boot:run"

    # start again
    cd examples/order-service
    export JAVA_HOME=/path/to/jdk-17
    mvn spring-boot:run -Dspring-boot.run.profiles=local

    # database
    docker stop tl-orders-pg      # keeps data
    docker start tl-orders-pg
    docker rm -f tl-orders-pg     # destroys it; re-run local/init.sql after recreating

## 4. Try to break it

Worth doing, because the failures are the interesting part:

- Send `X-Tenant-ID: someone-who-has-never-ordered` → empty list, never someone else's rows.
- In pgAdmin as `orders_app` with no tenant set, run `select * from orders` → 0 rows. A
  forgotten tenant returns nothing rather than everything.
- Stop the service, comment the dependency out of `pom.xml`, restart → `POST /orders`
  returns 500, because nothing supplies the tenant the column defaults from.
