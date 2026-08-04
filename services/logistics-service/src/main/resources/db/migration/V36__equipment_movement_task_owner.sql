ALTER TABLE public.equipment_movement_task
    ADD COLUMN owner_type varchar(32),
    ADD COLUMN owner_id uuid;

-- All pre-existing tasks were submitted through the public boundary. They deliberately keep no
-- external owner id, while a maintenance disposition is permanently unique by its decision id.
UPDATE public.equipment_movement_task
SET owner_type = 'USER_REQUEST'
WHERE owner_type IS NULL;

ALTER TABLE public.equipment_movement_task
    ALTER COLUMN owner_type SET NOT NULL,
    ADD CONSTRAINT ck_equipment_movement_task_owner CHECK (
        (owner_type = 'USER_REQUEST' AND owner_id IS NULL)
        OR (owner_type = 'MAINTENANCE_DISPOSITION' AND owner_id IS NOT NULL)
    ),
    ADD CONSTRAINT uk_equipment_movement_task_owner UNIQUE (owner_type, owner_id);
