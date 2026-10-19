create table contractor_route_share (
    id uuid primary key,
    version bigint not null default 0,
    warehouse_id uuid not null,
    contractor_worker_id uuid not null,
    created_by_subject_id uuid not null,
    idempotency_key uuid not null,
    request_sha256 varchar(64) not null,
    token_revision bigint not null default 1,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_contractor_route_share_subject_key
        unique (created_by_subject_id, idempotency_key),
    constraint ck_contractor_route_share_version check (version >= 0),
    constraint ck_contractor_route_share_request_sha256
        check (request_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_contractor_route_share_token_revision check (token_revision >= 1),
    constraint ck_contractor_route_share_expiry check (expires_at > created_at),
    constraint ck_contractor_route_share_revocation
        check (revoked_at is null or revoked_at >= created_at)
);

create index idx_contractor_route_share_warehouse_expiry
    on contractor_route_share (warehouse_id, expires_at, id)
    where revoked_at is null;

create index idx_contractor_route_share_contractor_expiry
    on contractor_route_share (contractor_worker_id, expires_at, id)
    where revoked_at is null;

create table contractor_route_share_task (
    id uuid primary key,
    share_id uuid not null,
    position integer not null,
    external_task_id uuid not null,
    driver_task_id uuid not null,
    document_id uuid not null,
    rental_order_id uuid,
    constraint fk_contractor_route_share_task_share
        foreign key (share_id) references contractor_route_share(id) on delete cascade,
    constraint uk_contractor_route_share_task_position unique (share_id, position),
    constraint uk_contractor_route_share_task_external unique (share_id, external_task_id),
    constraint ck_contractor_route_share_task_position check (position >= 0)
);

create index idx_contractor_route_share_task_external
    on contractor_route_share_task (external_task_id, share_id);
