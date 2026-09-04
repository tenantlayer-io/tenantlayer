-- Applied by the privileged (superuser) connection before each test class.
-- This is what feature 30 (RLS policy generation) will eventually emit.

drop table if exists documents;

create table documents (
    id        bigserial primary key,
    tenant_id varchar(64)  not null,
    title     varchar(255) not null
);

-- Mandatory. Without it the policy predicate turns every lookup into a filtered scan.
create index idx_documents_tenant on documents (tenant_id);

alter table documents enable row level security;

-- FORCE so the table owner is subject to the policy too. Superusers still bypass,
-- which is exactly why the application connects as a least-privileged role below.
alter table documents force row level security;

-- nullif(...,'') is the guard: after a RESET, current_setting returns '' rather than
-- NULL, and comparing '' to a typed column is a runtime error in the general case.
create policy tenant_isolation on documents
    using (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''));

insert into documents (tenant_id, title) values
    ('acme',   'acme quarterly report'),
    ('acme',   'acme roadmap'),
    ('globex', 'globex board minutes'),
    ('globex', 'globex payroll'),
    ('globex', 'globex acquisition memo');

-- Feature 50/56 — the tenant registry. Note deliberately NO row level security: this is
-- shared infrastructure consulted during resolution, before any tenant is known.
drop table if exists tenantlayer_tenants;

create table tenantlayer_tenants (
    tenant_id      varchar(64) primary key,
    status         varchar(16)  not null default 'ACTIVE',
    region         varchar(64),
    tenant_group   varchar(64),
    datasource_ref varchar(128),
    metadata       jsonb        not null default '{}'::jsonb
);

insert into tenantlayer_tenants (tenant_id, status, region, tenant_group, metadata) values
    ('acme',    'ACTIVE',    'eu-west-1', 'direct',      '{"plan":"pro"}'),
    ('globex',  'ACTIVE',    'us-east-1', 'acme-partners', '{"plan":"free"}'),
    ('initech', 'SUSPENDED', 'eu-west-1', 'acme-partners', '{}');

-- Feature 20 — discriminator strategy. Deliberately NO row level security: any isolation
-- observed on this table is Hibernate's @TenantId doing the work, not Postgres.
drop table if exists notes;

create table notes (
    id        bigserial primary key,
    tenant_id varchar(64)  not null,
    body      varchar(255) not null
);

insert into notes (tenant_id, body) values
    ('acme',   'acme note one'),
    ('acme',   'acme note two'),
    ('globex', 'globex note one'),
    ('globex', 'globex note two'),
    ('globex', 'globex note three');

-- Feature 30 — starts with no policy at all. The generator's output is applied by the
-- test, and isolation is then asserted on it. If the generated SQL is wrong, acme reads
-- globex's invoices and the test fails.
drop table if exists invoices;

create table invoices (
    id        bigserial primary key,
    tenant_id varchar(64)  not null,
    reference varchar(255) not null
);

insert into invoices (tenant_id, reference) values
    ('acme',   'ACME-001'),
    ('globex', 'GLOBEX-001'),
    ('globex', 'GLOBEX-002');
