-- The former MOVE_TO_SHIPMENT kind duplicated the existing post-repair movement.
-- Preserve active/history rows under the canonical REMOVE_FROM_REPAIR kind and reject
-- creation of the invented kind from this schema version onward.
UPDATE public.driver_logistics_task
SET task_kind = 'REMOVE_FROM_REPAIR'
WHERE task_kind = 'MOVE_TO_SHIPMENT';

ALTER TABLE public.driver_logistics_task
  DROP CONSTRAINT ck_driver_logistics_task_kind,
  ADD CONSTRAINT ck_driver_logistics_task_kind
    CHECK (task_kind IN (
      'DELIVER_TO_REPAIR', 'REMOVE_FROM_REPAIR', 'CAPITAL_TO_PRODUCTION',
      'GENERAL_MOVEMENT'
    ));

-- Keep the former column for one expand/contract window, but remove it from all
-- invariants. Repair continuation now needs only its priority; the outbound leg
-- is always the canonical REMOVE_FROM_REPAIR task.
ALTER TABLE public.logistics_document_line
  DROP CONSTRAINT ck_logistics_document_line_repair_continuation,
  ADD CONSTRAINT ck_logistics_document_line_repair_continuation
    CHECK (
      (active_repair_id IS NULL
        AND repair_continuation_priority IS NULL
        AND maintenance_arrival_completed_at IS NULL)
      OR
      (active_repair_id IS NOT NULL
        AND (
          (repair_continuation_priority IS NULL
            AND maintenance_arrival_completed_at IS NULL)
          OR repair_continuation_priority IS NOT NULL
        ))
    );
