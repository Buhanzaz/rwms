-- Authorize media for the cabins migrated from old-panel into asset-service.
-- The migration is intentionally limited to the deterministic migration IDs;
-- newly created cabins remain fail-closed until an authoritative asset owner
-- projection is introduced.

alter table media_owner_binding
    drop constraint media_owner_binding_owner_type_check;
alter table media_owner_binding
    drop constraint media_owner_binding_proof_aggregate_type_check;
alter table media_owner_binding
    add constraint media_owner_binding_owner_proof_scope_check check (
        (owner_type = 'INVENTORY_FINDING' and proof_aggregate_type = 'FINDING')
        or (owner_type = 'CABIN' and proof_aggregate_type = 'RENTAL_ITEM')
    );

with migrated_cabin as (
    select
        format(
            '51000000-0000-4000-8000-%s',
            lpad(sequence_number::text, 12, '0')
        )::uuid as cabin_id
    from generate_series(1, 195) as sequence_number
)
insert into media_consumer_aggregate_checkpoint (
    consumer_name, aggregate_type, aggregate_id, aggregate_version
)
select
    'media-service-old-panel-cabin-migration-v1',
    'RENTAL_ITEM',
    cabin_id,
    0
from migrated_cabin;

with migrated_cabin as (
    select
        sequence_number,
        format(
            '51000000-0000-4000-8000-%s',
            lpad(sequence_number::text, 12, '0')
        )::uuid as cabin_id,
        format(
            '52000000-0000-4000-8000-%s',
            lpad(sequence_number::text, 12, '0')
        )::uuid as proof_event_id
    from generate_series(1, 195) as sequence_number
)
insert into media_owner_binding (
    owner_type,
    owner_id,
    warehouse_id,
    owner_revision,
    proof_event_id,
    proof_consumer_name,
    proof_aggregate_type,
    proof_aggregate_id,
    proof_aggregate_version,
    proof_recorded_at,
    active
)
select
    'CABIN',
    cabin_id::text,
    case
        when sequence_number <= 120
            then '00000000-0000-0000-0000-000000000001'::uuid
        else '00000000-0000-0000-0000-000000000002'::uuid
    end,
    0,
    proof_event_id,
    'media-service-old-panel-cabin-migration-v1',
    'RENTAL_ITEM',
    cabin_id,
    0,
    '2026-07-19T00:00:00Z'::timestamptz,
    true
from migrated_cabin;

do $$
declare
    seeded_count integer;
    saint_petersburg_count integer;
    moscow_count integer;
begin
    select
        count(*),
        count(*) filter (
            where warehouse_id = '00000000-0000-0000-0000-000000000001'::uuid
        ),
        count(*) filter (
            where warehouse_id = '00000000-0000-0000-0000-000000000002'::uuid
        )
    into seeded_count, saint_petersburg_count, moscow_count
    from media_owner_binding
    where owner_type = 'CABIN'
      and proof_consumer_name = 'media-service-old-panel-cabin-migration-v1';

    if seeded_count <> 195
        or saint_petersburg_count <> 120
        or moscow_count <> 75 then
        raise exception
            'Migrated cabin media bindings are incomplete: total %, SPB %, MSK %',
            seeded_count,
            saint_petersburg_count,
            moscow_count;
    end if;
end
$$;
