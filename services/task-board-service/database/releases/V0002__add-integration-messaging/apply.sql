ALTER TABLE public.board_task
  ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64);
DO $rwms$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_board_task_request_fingerprint'
      AND conrelid = 'public.board_task'::regclass
  ) THEN
    ALTER TABLE public.board_task
      ADD CONSTRAINT ck_board_task_request_fingerprint
      CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');
  END IF;
END $rwms$;

CREATE TABLE IF NOT EXISTS public.task_board_outbox (
  event_id uuid PRIMARY KEY,
  event_type varchar(128) NOT NULL,
  event_version integer NOT NULL,
  routing_key varchar(160) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  envelope_body text NOT NULL,
  envelope_sha256 varchar(64) NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  actor_id varchar(128),
  actor_type varchar(64),
  actor_display_name varchar(256),
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_until timestamptz,
  lease_owner varchar(128),
  lease_token uuid,
  published_at timestamptz,
  last_error varchar(1000),
  CONSTRAINT uk_task_board_outbox_semantic
    UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_task_board_outbox_event_version CHECK (event_version > 0),
  CONSTRAINT ck_task_board_outbox_aggregate_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_task_board_outbox_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT ck_task_board_outbox_body CHECK (
    octet_length(envelope_body) BETWEEN 1 AND 1048576
    AND envelope_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_task_board_outbox_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED')),
  CONSTRAINT ck_task_board_outbox_lease CHECK (
    (status = 'IN_FLIGHT'
      AND lease_until IS NOT NULL AND lease_owner IS NOT NULL AND lease_token IS NOT NULL)
    OR
    (status <> 'IN_FLIGHT'
      AND lease_until IS NULL AND lease_owner IS NULL AND lease_token IS NULL)
  ),
  CONSTRAINT ck_task_board_outbox_published CHECK (
    (status = 'PUBLISHED') = (published_at IS NOT NULL)
  ),
  CONSTRAINT ck_task_board_outbox_contract CHECK (
    event_version = 1 AND (
    (event_type = 'task-board.board-task.created'
      AND routing_key = 'task-board.board-task.created.v1')
    OR
    (event_type = 'task-board.board-task.cancelled'
      AND routing_key = 'task-board.board-task.cancelled.v1')
    )
  )
);
CREATE INDEX IF NOT EXISTS idx_task_board_outbox_pending
  ON public.task_board_outbox(next_attempt_at, occurred_at, event_id)
  WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_task_board_outbox_expired_lease
  ON public.task_board_outbox(lease_until, occurred_at, event_id)
  WHERE status = 'IN_FLIGHT';
CREATE INDEX IF NOT EXISTS idx_task_board_outbox_aggregate_head
  ON public.task_board_outbox(aggregate_type, aggregate_id, aggregate_version, occurred_at, event_id)
  WHERE status <> 'PUBLISHED';

CREATE TABLE IF NOT EXISTS public.task_board_inbox (
  consumer_name varchar(128) NOT NULL,
  event_id uuid NOT NULL,
  event_hash varchar(64) NOT NULL,
  event_type varchar(128) NOT NULL,
  event_version integer NOT NULL,
  received_at timestamptz NOT NULL,
  processed_at timestamptz NOT NULL,
  CONSTRAINT pk_task_board_inbox PRIMARY KEY (consumer_name, event_id),
  CONSTRAINT ck_task_board_inbox_hash CHECK (event_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_task_board_inbox_version CHECK (event_version > 0)
);
