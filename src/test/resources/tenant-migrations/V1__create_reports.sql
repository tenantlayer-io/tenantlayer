create table reports (
    id        bigserial primary key,
    tenant_id varchar(64),
    title     varchar(255) not null
);
