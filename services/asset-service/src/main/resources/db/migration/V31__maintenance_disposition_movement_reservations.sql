-- Maintenance disposition worker moves are a closed subtype of the existing
-- logistics movement reservation. They still require one concrete source and
-- one active reservation per movementId:lineId, including across both owner
-- subtypes.

ALTER TABLE public.equipment_allocation_hold
  DROP CONSTRAINT ck_equipment_hold_movement_source;

ALTER TABLE public.equipment_allocation_hold
  ADD CONSTRAINT ck_equipment_hold_movement_source CHECK (
    owner_type NOT IN (
      'LOGISTICS_EQUIPMENT_MOVEMENT',
      'MAINTENANCE_DISPOSITION_MOVEMENT'
    )
    OR source_balance_id IS NOT NULL
  );

DROP INDEX public.uk_equipment_hold_active_movement_line;

CREATE UNIQUE INDEX uk_equipment_hold_active_movement_line
  ON public.equipment_allocation_hold(owner_id)
  WHERE owner_type IN (
    'LOGISTICS_EQUIPMENT_MOVEMENT',
    'MAINTENANCE_DISPOSITION_MOVEMENT'
  )
  AND state = 'ACTIVE';
