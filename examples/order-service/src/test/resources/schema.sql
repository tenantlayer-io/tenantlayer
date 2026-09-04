-- Applied by an admin connection before the service starts.

drop table if exists orders;

create table orders (
    id           bigserial primary key,
    -- The service never writes this. The connection's tenant fills it in.
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

-- The tenant registry (features 50/56). No row level security: this is the table the
-- library consults to find out who the tenants are, before any tenant is known.
drop table if exists tenantlayer_tenants;

create table tenantlayer_tenants (
    tenant_id      varchar(64) primary key,
    status         varchar(16)  not null default 'ACTIVE',
    region         varchar(64),
    tenant_group   varchar(64),
    datasource_ref varchar(128),
    metadata       jsonb        not null default '{}'::jsonb
);

insert into tenantlayer_tenants (tenant_id, status, region) values
    ('acme',    'ACTIVE',    'eu-west-1'),
    ('globex',  'ACTIVE',    'us-east-1'),
    ('initech', 'SUSPENDED', 'eu-west-1');
