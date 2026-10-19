-- Warehouse-scoped native feed fencing and immutable worker-action replay receipts.

CREATE SEQUENCE public.worker_feed_revision_seq;

SELECT setval(
  'public.worker_feed_revision_seq',
  greatest(
    coalesce((SELECT sum(current_version + 1) FROM public.event_stream_head), 0),
    1)::bigint,
  true);

CREATE TABLE public.worker_feed_revision (
  warehouse_id uuid NOT NULL,
  revision bigint NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT worker_feed_revision_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT ck_worker_feed_revision_positive CHECK (revision > 0)
);

WITH known_warehouse AS (
  SELECT id AS warehouse_id FROM public.warehouse_metadata
  UNION
  SELECT warehouse_id FROM public.board_task
  UNION
  SELECT warehouse_id FROM public.work_queue
  UNION
  SELECT warehouse_id FROM public.worker
  UNION
  SELECT warehouse_id FROM public.worker_group
  UNION
  SELECT warehouse_id FROM public.warehouse_kpi_settings
  UNION
  SELECT warehouse_id FROM public.worker_task_evidence
)
INSERT INTO public.worker_feed_revision(warehouse_id, revision, updated_at)
SELECT warehouse_id, nextval('public.worker_feed_revision_seq'), clock_timestamp()
FROM known_warehouse
WHERE warehouse_id IS NOT NULL
ORDER BY warehouse_id;

CREATE TABLE public.worker_action_receipt (
  operation_id uuid NOT NULL,
  app_surface varchar(16) NOT NULL,
  worker_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  entry_id uuid NOT NULL,
  action varchar(16) NOT NULL,
  request_body text NOT NULL,
  request_sha256 char(64) NOT NULL,
  response_body text NOT NULL,
  response_sha256 char(64) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT worker_action_receipt_pkey PRIMARY KEY (operation_id),
  CONSTRAINT ck_worker_action_receipt_surface
    CHECK (app_surface IN ('WORKER', 'DRIVER')),
  CONSTRAINT ck_worker_action_receipt_action
    CHECK (action IN ('TAKE', 'JOIN', 'PAUSE', 'RESUME', 'COMPLETE')),
  CONSTRAINT ck_worker_action_receipt_request
    CHECK (
      jsonb_typeof(request_body::jsonb) = 'object'
      AND request_sha256 ~ '^[0-9a-f]{64}$'
      AND request_sha256 = encode(sha256(convert_to(request_body, 'UTF8')), 'hex')
    ),
  CONSTRAINT ck_worker_action_receipt_response
    CHECK (
      jsonb_typeof(response_body::jsonb) = 'object'
      AND response_sha256 ~ '^[0-9a-f]{64}$'
      AND response_sha256 = encode(sha256(convert_to(response_body, 'UTF8')), 'hex')
    )
);

CREATE INDEX idx_worker_action_receipt_scope
  ON public.worker_action_receipt(warehouse_id, worker_id, created_at DESC);

CREATE INDEX idx_domain_event_correlation
  ON public.domain_event(correlation_id);
