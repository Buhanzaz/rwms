-- Worker result evidence and the two canonical task-board media aggregate families.
-- The projection is JDBC-owned so the existing JPA aggregate remains unchanged.

ALTER TABLE public.event_stream_head
  DROP CONSTRAINT ck_event_stream_head_type;
ALTER TABLE public.event_stream_head
  ADD CONSTRAINT ck_event_stream_head_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY',
    'TASK_BOARD_ENTRY_OWNER_PROOF', 'TASK_EVIDENCE'
  ));

ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_domain_event_type;
ALTER TABLE public.domain_event
  ADD CONSTRAINT ck_domain_event_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY',
    'TASK_BOARD_ENTRY_OWNER_PROOF', 'TASK_EVIDENCE'
  ));

ALTER TABLE public.projection_checkpoint
  DROP CONSTRAINT ck_projection_checkpoint_type;
ALTER TABLE public.projection_checkpoint
  ADD CONSTRAINT ck_projection_checkpoint_type CHECK (aggregate_type IN (
    'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
    'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY',
    'TASK_BOARD_ENTRY_OWNER_PROOF', 'TASK_EVIDENCE'
  ));

ALTER TABLE public.outbox_event
  DROP CONSTRAINT ck_outbox_event_topic;
ALTER TABLE public.outbox_event
  ADD CONSTRAINT ck_outbox_event_topic CHECK (
    (
      aggregate_type IN (
        'WORKER_CLASS', 'WORKER', 'WORKER_GROUP', 'WORK_QUEUE',
        'QUEUE_USAGE_REFERENCE', 'BOARD_TASK', 'QUEUE_ENTRY'
      )
      AND topic =
        'rwms.task-board.' || replace(lower(aggregate_type), '_', '-') || '.v1'
    )
    OR (
      aggregate_type = 'TASK_BOARD_ENTRY_OWNER_PROOF'
      AND topic = 'rwms.task-board.entry-owner-proof.v1'
    )
    OR (
      aggregate_type = 'TASK_EVIDENCE'
      AND topic = 'rwms.task-board.task-evidence.v1'
    )
  );

CREATE TABLE public.worker_task_evidence (
  evidence_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  operation_id uuid NOT NULL,
  entry_id uuid NOT NULL,
  task_id uuid NOT NULL,
  route_index integer NOT NULL,
  warehouse_id uuid NOT NULL,
  worker_id uuid NOT NULL,
  worker_group_id uuid,
  captured_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL,
  state varchar(24) NOT NULL,
  media_id uuid,
  media_generation bigint,
  review_reason varchar(512),
  content_type varchar(128) NOT NULL,
  size_bytes bigint NOT NULL,
  sha256 char(64) NOT NULL,
  source_type varchar(64),
  source_id uuid,
  updated_at timestamptz NOT NULL,
  CONSTRAINT worker_task_evidence_pkey PRIMARY KEY (evidence_id),
  CONSTRAINT uk_worker_task_evidence_operation UNIQUE (operation_id),
  CONSTRAINT fk_worker_task_evidence_entry
    FOREIGN KEY (entry_id) REFERENCES public.queue_entry(id),
  CONSTRAINT ck_worker_task_evidence_version CHECK (version >= 0),
  CONSTRAINT ck_worker_task_evidence_route CHECK (route_index >= 0),
  CONSTRAINT ck_worker_task_evidence_state CHECK (
    state IN ('RESERVED', 'UPLOADING', 'READY', 'REVIEW_REQUIRED', 'REJECTED')
  ),
  CONSTRAINT ck_worker_task_evidence_media CHECK (
    (state IN ('RESERVED', 'UPLOADING') AND media_id IS NULL AND media_generation IS NULL)
    OR
    (state IN ('READY', 'REVIEW_REQUIRED', 'REJECTED'))
  ),
  CONSTRAINT ck_worker_task_evidence_generation CHECK (
    media_generation IS NULL OR media_generation >= 1
  ),
  CONSTRAINT ck_worker_task_evidence_upload CHECK (
    content_type = 'image/jpeg'
    AND size_bytes BETWEEN 1 AND 15728640
    AND sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_worker_task_evidence_source CHECK (
    (source_type IS NULL AND source_id IS NULL)
    OR
    (source_type = 'MAINTENANCE_REPAIR' AND source_id IS NOT NULL)
  )
);

CREATE INDEX idx_worker_task_evidence_entry
  ON public.worker_task_evidence(entry_id, recorded_at, evidence_id);
CREATE INDEX idx_worker_task_evidence_worker
  ON public.worker_task_evidence(worker_id, state, updated_at);

CREATE TABLE public.worker_media_event_inbox (
  event_id uuid NOT NULL,
  media_id uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(96) NOT NULL,
  entry_id uuid NOT NULL,
  evidence_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  actor_worker_id uuid NOT NULL,
  correlation_id uuid NOT NULL,
  media_generation bigint,
  recorded_at timestamptz NOT NULL,
  body_sha256 char(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  failure_code varchar(64),
  received_at timestamptz NOT NULL,
  processed_at timestamptz,
  CONSTRAINT worker_media_event_inbox_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_worker_media_event_stream_version
    UNIQUE (media_id, aggregate_version),
  CONSTRAINT ck_worker_media_event_version CHECK (aggregate_version >= 1),
  CONSTRAINT ck_worker_media_event_type CHECK (
    event_type IN ('media.media.uploaded.v1', 'media.media.ready.v1', 'media.media.failed.v1')
  ),
  CONSTRAINT ck_worker_media_event_generation CHECK (
    media_generation IS NULL OR media_generation >= 1
  ),
  CONSTRAINT ck_worker_media_event_hash CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_worker_media_event_status CHECK (
    status IN ('PENDING', 'APPLIED', 'IGNORED', 'REJECTED')
  ),
  CONSTRAINT ck_worker_media_event_failure CHECK (
    failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'
  )
);

CREATE INDEX idx_worker_media_event_pending
  ON public.worker_media_event_inbox(received_at, event_id)
  WHERE status = 'PENDING';

CREATE TABLE public.worker_device_registration (
  installation_id varchar(128) NOT NULL,
  worker_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  provider varchar(16) NOT NULL,
  provider_token varchar(4096) NOT NULL,
  status varchar(16) NOT NULL,
  app_version varchar(64) NOT NULL,
  sdk_int integer NOT NULL,
  locale varchar(35) NOT NULL,
  registered_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT worker_device_registration_pkey PRIMARY KEY (installation_id),
  CONSTRAINT fk_worker_device_registration_worker
    FOREIGN KEY (worker_id) REFERENCES public.worker(id) ON DELETE CASCADE,
  CONSTRAINT ck_worker_device_provider CHECK (provider = 'FCM'),
  CONSTRAINT ck_worker_device_status CHECK (status = 'ACTIVE'),
  CONSTRAINT ck_worker_device_token CHECK (
    provider_token = btrim(provider_token) AND provider_token <> ''
  ),
  CONSTRAINT ck_worker_device_sdk CHECK (sdk_int BETWEEN 23 AND 1000)
);

CREATE UNIQUE INDEX uk_worker_device_provider_token
  ON public.worker_device_registration(provider, provider_token);
CREATE INDEX idx_worker_device_worker
  ON public.worker_device_registration(worker_id, updated_at);
