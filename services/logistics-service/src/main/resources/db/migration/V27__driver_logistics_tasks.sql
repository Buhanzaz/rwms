CREATE TABLE public.driver_logistics_task (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  cabin_id uuid NOT NULL,
  repair_id uuid,
  source_type varchar(32) NOT NULL,
  source_id uuid NOT NULL,
  task_kind varchar(32) NOT NULL,
  planning_mode varchar(16) NOT NULL,
  scheduled_date date NOT NULL,
  priority integer NOT NULL,
  unit_number varchar(64) NOT NULL,
  driver_queue_definition_id uuid NOT NULL,
  external_task_id uuid NOT NULL,
  task_board_task_id uuid,
  task_board_task_version bigint,
  task_board_entry_id uuid,
  task_board_entry_status varchar(24),
  task_board_done_at timestamptz,
  state varchar(32) NOT NULL,
  repair_place_allocation_id uuid,
  repair_place_allocation_version bigint,
  completion_evidence_id uuid,
  completion_media_id uuid,
  completion_media_generation bigint,
  completion_entry_id uuid,
  cover_applied boolean NOT NULL DEFAULT false,
  repair_place_effect_applied boolean NOT NULL DEFAULT false,
  created_by_subject_id uuid NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 char(64) NOT NULL,
  retry_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  failure_code varchar(96),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT uk_driver_logistics_task_external UNIQUE (external_task_id),
  CONSTRAINT uk_driver_logistics_task_source_kind
    UNIQUE (source_type, source_id, task_kind),
  CONSTRAINT uk_driver_logistics_task_creator_key
    UNIQUE (created_by_subject_id, idempotency_key),
  CONSTRAINT ck_driver_logistics_task_source
    CHECK (source_type IN (
      'REPAIR', 'ESTIMATE', 'INVENTORY', 'REPAIR_PLACE', 'CAPITAL_REPAIR'
    )),
  CONSTRAINT ck_driver_logistics_task_kind
    CHECK (task_kind IN (
      'DELIVER_TO_REPAIR', 'REMOVE_FROM_REPAIR',
      'CAPITAL_TO_PRODUCTION', 'MOVE_TO_SHIPMENT'
    )),
  CONSTRAINT ck_driver_logistics_task_planning
    CHECK (planning_mode IN ('AUTO', 'FIXED_DATE')),
  CONSTRAINT ck_driver_logistics_task_state
    CHECK (state IN (
      'REGISTERING', 'SCHEDULED', 'CURRENT', 'FINALIZING',
      'COMPLETED', 'CANCELLED', 'RECONCILIATION_REQUIRED'
    )),
  CONSTRAINT ck_driver_logistics_task_priority CHECK (priority BETWEEN 1 AND 5),
  CONSTRAINT ck_driver_logistics_task_task_version
    CHECK (task_board_task_version IS NULL OR task_board_task_version >= 0),
  CONSTRAINT ck_driver_logistics_task_allocation_version
    CHECK (
      repair_place_allocation_version IS NULL
      OR repair_place_allocation_version >= 0
    ),
  CONSTRAINT ck_driver_logistics_task_media_generation
    CHECK (
      completion_media_generation IS NULL
      OR completion_media_generation > 0
    ),
  CONSTRAINT ck_driver_logistics_task_retry_count CHECK (retry_count >= 0),
  CONSTRAINT ck_driver_logistics_task_request_sha
    CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_driver_logistics_task_repair_context
    CHECK (
      task_kind NOT IN ('DELIVER_TO_REPAIR', 'REMOVE_FROM_REPAIR')
      OR repair_id IS NOT NULL
    ),
  CONSTRAINT ck_driver_logistics_task_board_reference
    CHECK (
      (task_board_task_id IS NULL
        AND task_board_task_version IS NULL
        AND task_board_entry_id IS NULL
        AND task_board_entry_status IS NULL)
      OR
      (task_board_task_id IS NOT NULL
        AND task_board_task_version IS NOT NULL
        AND task_board_entry_id IS NOT NULL
        AND task_board_entry_status IS NOT NULL)
    ),
  CONSTRAINT ck_driver_logistics_task_evidence
    CHECK (
      (completion_evidence_id IS NULL
        AND completion_media_id IS NULL
        AND completion_media_generation IS NULL
        AND completion_entry_id IS NULL)
      OR
      (completion_evidence_id IS NOT NULL
        AND completion_media_id IS NOT NULL
        AND completion_media_generation IS NOT NULL
        AND completion_entry_id IS NOT NULL)
    )
);

CREATE INDEX ix_driver_logistics_task_due
  ON public.driver_logistics_task(state, next_attempt_at, id);

CREATE INDEX ix_driver_logistics_task_warehouse_schedule
  ON public.driver_logistics_task(warehouse_id, state, scheduled_date, priority, created_at, id);

CREATE INDEX ix_driver_logistics_task_repair
  ON public.driver_logistics_task(repair_id)
  WHERE repair_id IS NOT NULL;

CREATE UNIQUE INDEX uk_driver_logistics_task_one_current
  ON public.driver_logistics_task(warehouse_id)
  WHERE state = 'CURRENT';
