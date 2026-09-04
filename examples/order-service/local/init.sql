-- Persistent demo database for the order-service.
-- Run as the admin (superuser) role.

drop table if exists orders;
drop role if exists orders_app;

-- The role the SERVICE connects as. Deliberately neither superuser nor table owner:
-- a superuser bypasses row-level security entirely, so the policy would never apply.
create role orders_app login password 'orders_pwd';

create table orders (
    id           bigserial primary key,
    -- The service never writes this. It defaults to the tenant on the connection.
    tenant_id    varchar(64)  not null default current_setting('tenantlayer.tenant', true),
    customer     varchar(255) not null,
    item         varchar(255) not null,
    amount_cents bigint       not null,
    status       varchar(32)  not null,
    placed_at    timestamptz  not null default now()
);

create index idx_orders_tenant on orders (tenant_id);

alter table orders enable row level security;
alter table orders force row level security;

create policy tenant_isolation on orders
    using (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''));

grant usage on schema public to orders_app;
grant select, insert, update, delete on orders to orders_app;
grant usage, select on all sequences in schema public to orders_app;
