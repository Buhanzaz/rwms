-- A manual CURRENT -> SCHEDULED move pauses automatic queue filling only for the configured
-- maintenance interval. Keep the former durable boolean only long enough to migrate existing
-- operator intent; it must not survive as a second scheduling source of truth.
ALTER TABLE public.driver_logistics_task
  ADD COLUMN manual_promotion_hold_until timestamptz,
  ADD COLUMN fixed_date_lower_bound date,
  ADD COLUMN movement_comment varchar(1000);

UPDATE public.driver_logistics_task
SET manual_promotion_hold_until = updated_at + interval '5 minutes'
WHERE manual_promotion_hold;

UPDATE public.driver_logistics_task
SET fixed_date_lower_bound = scheduled_date
WHERE planning_mode = 'FIXED_DATE';

DROP INDEX IF EXISTS public.ix_driver_logistics_task_manual_hold;

ALTER TABLE public.driver_logistics_task
  DROP CONSTRAINT ck_driver_logistics_task_source,
  DROP CONSTRAINT ck_driver_logistics_task_kind,
  ADD CONSTRAINT ck_driver_logistics_task_source
    CHECK (source_type IN (
      'REPAIR', 'ESTIMATE', 'INVENTORY', 'REPAIR_PLACE', 'CAPITAL_REPAIR', 'MANUAL'
    )),
  ADD CONSTRAINT ck_driver_logistics_task_kind
    CHECK (task_kind IN (
      'DELIVER_TO_REPAIR', 'REMOVE_FROM_REPAIR', 'CAPITAL_TO_PRODUCTION',
      'MOVE_TO_SHIPMENT', 'GENERAL_MOVEMENT'
    )),
  ADD CONSTRAINT ck_driver_logistics_task_manual_movement
    CHECK (
      (source_type = 'MANUAL' AND task_kind = 'GENERAL_MOVEMENT')
      OR (source_type <> 'MANUAL' AND task_kind <> 'GENERAL_MOVEMENT')
    ),
  ADD CONSTRAINT ck_driver_logistics_task_manual_comment
    CHECK (
      source_type <> 'MANUAL'
      OR (movement_comment IS NOT NULL AND length(btrim(movement_comment)) > 0)
    ),
  ADD CONSTRAINT ck_driver_logistics_task_fixed_lower_bound
    CHECK (
      (planning_mode = 'AUTO' AND fixed_date_lower_bound IS NULL)
      OR (planning_mode = 'FIXED_DATE' AND fixed_date_lower_bound IS NOT NULL)
    ),
  DROP COLUMN manual_promotion_hold;

CREATE INDEX ix_driver_logistics_task_manual_hold_until
  ON public.driver_logistics_task(warehouse_id, manual_promotion_hold_until, state)
  WHERE manual_promotion_hold_until IS NOT NULL;
