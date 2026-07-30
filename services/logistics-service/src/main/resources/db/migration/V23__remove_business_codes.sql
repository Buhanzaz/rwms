-- Business entities are identified only by UUID. Human-readable equipment
-- names remain immutable display snapshots; legacy technical codes are removed.

ALTER TABLE public.equipment_movement_task_line
    DROP CONSTRAINT IF EXISTS ck_equipment_movement_task_line_equipment_code,
    DROP COLUMN IF EXISTS equipment_code;

DROP INDEX IF EXISTS public.idx_order_equipment_requirement_order_unit;

ALTER TABLE public.rental_order_equipment_requirement
    DROP CONSTRAINT IF EXISTS ck_order_equipment_requirement_code,
    DROP COLUMN IF EXISTS equipment_code;
