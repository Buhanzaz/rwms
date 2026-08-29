ALTER TABLE public.customer_scenario_capacity_snapshot
    RENAME TO customer_warehouse_capacity_snapshot;
ALTER TABLE public.customer_scenario_capacity_command_receipt
    RENAME TO customer_warehouse_capacity_command_receipt;
ALTER TABLE public.customer_scenario_capacity_job
    RENAME TO customer_warehouse_capacity_job;
ALTER TABLE public.customer_scenario_capacity_shift
    RENAME TO customer_warehouse_capacity_shift;
ALTER TABLE public.customer_scenario_capacity_price_zone
    RENAME TO customer_warehouse_capacity_price_zone;

ALTER TABLE public.customer_warehouse_capacity_snapshot
    RENAME CONSTRAINT customer_scenario_capacity_snapshot_pkey
        TO customer_warehouse_capacity_snapshot_pkey;
ALTER TABLE public.customer_warehouse_capacity_snapshot
    RENAME CONSTRAINT uk_customer_scenario_capacity_snapshot_warehouse
        TO uk_customer_warehouse_capacity_snapshot_warehouse;
ALTER TABLE public.customer_warehouse_capacity_snapshot
    RENAME CONSTRAINT ck_customer_scenario_capacity_snapshot_version
        TO ck_customer_warehouse_capacity_snapshot_version;
ALTER TABLE public.customer_warehouse_capacity_snapshot
    RENAME CONSTRAINT ck_customer_scenario_capacity_snapshot_generation
        TO ck_customer_warehouse_capacity_snapshot_generation;
ALTER TABLE public.customer_warehouse_capacity_snapshot
    RENAME CONSTRAINT ck_customer_scenario_capacity_snapshot_source_revision
        TO ck_customer_warehouse_capacity_snapshot_source_revision;

ALTER TABLE public.customer_warehouse_capacity_command_receipt
    RENAME CONSTRAINT customer_scenario_capacity_command_receipt_pkey
        TO customer_warehouse_capacity_command_receipt_pkey;
ALTER TABLE public.customer_warehouse_capacity_command_receipt
    RENAME CONSTRAINT ck_customer_scenario_capacity_command_receipt_version
        TO ck_customer_warehouse_capacity_command_receipt_version;
ALTER TABLE public.customer_warehouse_capacity_command_receipt
    RENAME CONSTRAINT ck_customer_scenario_capacity_command_receipt_source_revision
        TO ck_customer_warehouse_capacity_command_receipt_source_revision;
ALTER TABLE public.customer_warehouse_capacity_command_receipt
    RENAME CONSTRAINT ck_customer_scenario_capacity_command_receipt_request_sha256
        TO ck_customer_warehouse_capacity_command_receipt_request_sha256;
ALTER TABLE public.customer_warehouse_capacity_command_receipt
    RENAME CONSTRAINT ck_customer_scenario_capacity_command_receipt_shift_count
        TO ck_customer_warehouse_capacity_command_receipt_shift_count;
ALTER TABLE public.customer_warehouse_capacity_command_receipt
    RENAME CONSTRAINT ck_customer_scenario_capacity_command_receipt_price_zone_count
        TO ck_customer_warehouse_capacity_command_receipt_price_zone_count;

ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT customer_scenario_capacity_job_pkey
        TO customer_warehouse_capacity_job_pkey;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT fk_customer_scenario_capacity_job_snapshot
        TO fk_customer_warehouse_capacity_job_snapshot;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT uk_customer_scenario_capacity_job_source
        TO uk_customer_warehouse_capacity_job_source;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT ck_customer_scenario_capacity_job_coordinates
        TO ck_customer_warehouse_capacity_job_coordinates;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT ck_customer_scenario_capacity_job_window
        TO ck_customer_warehouse_capacity_job_window;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT ck_customer_scenario_capacity_job_capacity
        TO ck_customer_warehouse_capacity_job_capacity;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT ck_customer_scenario_capacity_job_task_type
        TO ck_customer_warehouse_capacity_job_task_type;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT ck_customer_scenario_capacity_job_priority
        TO ck_customer_warehouse_capacity_job_priority;
ALTER TABLE public.customer_warehouse_capacity_job
    RENAME CONSTRAINT ck_customer_scenario_capacity_job_delivery_mandatory
        TO ck_customer_warehouse_capacity_job_delivery_mandatory;

ALTER TABLE public.customer_warehouse_capacity_shift
    RENAME CONSTRAINT customer_scenario_capacity_shift_pkey
        TO customer_warehouse_capacity_shift_pkey;
ALTER TABLE public.customer_warehouse_capacity_shift
    RENAME CONSTRAINT fk_customer_scenario_capacity_shift_snapshot
        TO fk_customer_warehouse_capacity_shift_snapshot;
ALTER TABLE public.customer_warehouse_capacity_shift
    RENAME CONSTRAINT uk_customer_scenario_capacity_shift_source
        TO uk_customer_warehouse_capacity_shift_source;
ALTER TABLE public.customer_warehouse_capacity_shift
    RENAME CONSTRAINT ck_customer_scenario_capacity_shift_window
        TO ck_customer_warehouse_capacity_shift_window;
ALTER TABLE public.customer_warehouse_capacity_shift
    RENAME CONSTRAINT ck_customer_scenario_capacity_shift_capacity
        TO ck_customer_warehouse_capacity_shift_capacity;

ALTER TABLE public.customer_warehouse_capacity_price_zone
    RENAME CONSTRAINT customer_scenario_capacity_price_zone_pkey
        TO customer_warehouse_capacity_price_zone_pkey;
ALTER TABLE public.customer_warehouse_capacity_price_zone
    RENAME CONSTRAINT fk_customer_scenario_capacity_price_zone_snapshot
        TO fk_customer_warehouse_capacity_price_zone_snapshot;
ALTER TABLE public.customer_warehouse_capacity_price_zone
    RENAME CONSTRAINT uk_customer_scenario_capacity_price_zone_source
        TO uk_customer_warehouse_capacity_price_zone_source;
ALTER TABLE public.customer_warehouse_capacity_price_zone
    RENAME CONSTRAINT ck_customer_scenario_capacity_price_zone_geometry
        TO ck_customer_warehouse_capacity_price_zone_geometry;

ALTER INDEX public.idx_customer_scenario_capacity_job_date
    RENAME TO idx_customer_warehouse_capacity_job_date;
ALTER INDEX public.idx_customer_scenario_capacity_shift_date
    RENAME TO idx_customer_warehouse_capacity_shift_date;

DROP INDEX public.idx_customer_scenario_capacity_command_revision;
DROP INDEX public.idx_customer_scenario_capacity_price_zone_snapshot;

ALTER TABLE public.customer_warehouse_capacity_snapshot
    DROP COLUMN source_scenario_id;
ALTER TABLE public.customer_warehouse_capacity_command_receipt
    DROP COLUMN source_scenario_id;

ALTER TABLE public.customer_delivery_slot
    ADD COLUMN price_zone_id uuid;

UPDATE public.customer_delivery_slot
SET price_zone_id = price_zone_code::uuid
WHERE price_zone_code ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

UPDATE public.customer_delivery_slot AS slot
SET price_zone_id = mapping.source_zone_id
FROM (
    SELECT snapshot.warehouse_id,
           zone.code,
           min(zone.source_zone_id::text)::uuid AS source_zone_id
    FROM public.customer_warehouse_capacity_snapshot AS snapshot
    JOIN public.customer_warehouse_capacity_price_zone AS zone
      ON zone.snapshot_id = snapshot.id
    GROUP BY snapshot.warehouse_id, zone.code
    HAVING count(DISTINCT zone.source_zone_id) = 1
) AS mapping
WHERE slot.price_zone_id IS NULL
  AND slot.warehouse_id = mapping.warehouse_id
  AND slot.price_zone_code = mapping.code;

ALTER TABLE public.customer_delivery_slot
    DROP CONSTRAINT ck_customer_delivery_slot_capacity,
    DROP COLUMN price_zone_code,
    ADD CONSTRAINT ck_customer_delivery_slot_capacity CHECK (
        cabin_count > 0
        AND one_way_travel_seconds >= 0
        AND travel_zone_hours >= 1
        AND capacity_remaining >= 0
        AND site_cabin_capacity BETWEEN 1 AND 2
        AND (delivery_price_rubles IS NULL OR delivery_price_rubles >= 0)
        AND (price_zone_id IS NULL OR delivery_price_rubles IS NOT NULL)
    );

CREATE INDEX idx_customer_warehouse_capacity_command_revision
    ON public.customer_warehouse_capacity_command_receipt (
        warehouse_id, source_generation, source_revision, created_at, idempotency_key
    );

ALTER TABLE public.customer_warehouse_capacity_price_zone
    DROP CONSTRAINT ck_customer_scenario_capacity_price_zone_values,
    DROP COLUMN code,
    DROP COLUMN priority,
    ADD CONSTRAINT ck_customer_warehouse_capacity_price_zone_values CHECK (
        source_zone_version >= 0
        AND delivery_price_rubles >= 0
        AND pickup_price_rubles >= 0
    );

CREATE INDEX idx_customer_warehouse_capacity_price_zone_snapshot
    ON public.customer_warehouse_capacity_price_zone (snapshot_id, source_zone_id);
