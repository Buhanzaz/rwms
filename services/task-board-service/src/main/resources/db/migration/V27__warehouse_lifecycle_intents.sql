-- A task-board admission decision must remain visible while the owner-side
-- lifecycle check is in flight.  These rows are deliberately local intent,
-- not a projection of warehouse lifecycle state: warehouse-service remains
-- the lifecycle authority.
CREATE TABLE public.task_board_warehouse_lifecycle_intent (
  intent_id uuid PRIMARY KEY,
  operation_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  intent_type varchar(16) NOT NULL,
  state varchar(16) NOT NULL,
  direction varchar(16),
  expected_warehouse_version bigint,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_task_board_warehouse_lifecycle_intent_type
    CHECK (intent_type IN ('ADMISSION', 'READINESS')),
  CONSTRAINT ck_task_board_warehouse_lifecycle_intent_state
    CHECK (
      (intent_type = 'ADMISSION' AND state IN ('RESERVED', 'ADMITTED'))
      OR (intent_type = 'READINESS' AND state = 'CONFIRMING')
    ),
  CONSTRAINT ck_task_board_warehouse_lifecycle_intent_direction
    CHECK (
      (intent_type = 'ADMISSION' AND direction IS NOT NULL
        AND direction IN ('INCOMING', 'OUTGOING')
        AND expected_warehouse_version IS NULL)
      OR (intent_type = 'READINESS' AND direction IS NULL
        AND expected_warehouse_version IS NOT NULL
        AND expected_warehouse_version >= 0)
    )
);

CREATE INDEX idx_task_board_warehouse_lifecycle_intent_blocking
  ON public.task_board_warehouse_lifecycle_intent(warehouse_id, intent_type, state);

CREATE INDEX idx_task_board_warehouse_lifecycle_intent_operation
  ON public.task_board_warehouse_lifecycle_intent(operation_id);

CREATE INDEX idx_task_board_warehouse_lifecycle_intent_expiration
  ON public.task_board_warehouse_lifecycle_intent(warehouse_id, expires_at);
