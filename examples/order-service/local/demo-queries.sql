-- Paste into pgAdmin's Query Tool against the "orders" database.
-- These show the mechanism from the database side.

-- 1. As admin you see EVERY tenant's rows.
--    Not a bug: superusers bypass row-level security entirely. This is exactly why the
--    service connects as orders_app instead.
select id, tenant_id, customer, item, amount_cents from orders order by id;

-- 2. Look at the policy itself.
select polname, pg_get_expr(polqual, polrelid) as using_expression
from pg_policy
where polrelid = 'orders'::regclass;

-- 3. Confirm RLS is enabled AND forced on the table.
select relname, relrowsecurity, relforcerowsecurity
from pg_class
where relname = 'orders';

-- 4. Now become the application role and watch isolation happen.
--    This is precisely what TenantLayer does on every connection checkout.
set role orders_app;

select set_config('tenantlayer.tenant', 'acme', false);
select id, tenant_id, item from orders;          -- acme's rows only

select set_config('tenantlayer.tenant', 'globex', false);
select id, tenant_id, item from orders;          -- globex's rows only

-- 5. The important one: no tenant set means NOTHING, never everything.
select set_config('tenantlayer.tenant', '', false);
select count(*) from orders;                     -- 0

-- 6. Even an explicit predicate for another tenant returns nothing. The filter is
--    applied by the database, so hand-written SQL cannot get around it.
select set_config('tenantlayer.tenant', 'acme', false);
select count(*) from orders where tenant_id = 'globex';   -- 0

reset role;
