-- RWMS maintenance-service owned cumulative schema. Legacy repair/catalog data
-- is imported only by the controlled draft import command, never by Flyway.

CREATE TABLE public.catalog_version (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  state varchar(16) NOT NULL,
  source_sha256 varchar(64) NOT NULL,
  node_count integer NOT NULL,
  link_count integer NOT NULL,
  validation_report varchar(16000) NOT NULL DEFAULT '{}',
  activated_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT catalog_version_pkey PRIMARY KEY (id),
  CONSTRAINT uk_catalog_version_source UNIQUE (warehouse_id, source_sha256),
  CONSTRAINT ck_catalog_version_version CHECK (version >= 0),
  CONSTRAINT ck_catalog_version_state CHECK (state IN ('DRAFT','ACTIVE','SUPERSEDED')),
  CONSTRAINT ck_catalog_version_source CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_catalog_version_counts CHECK (node_count >= 0 AND link_count >= 0)
);
CREATE UNIQUE INDEX uk_catalog_version_active ON public.catalog_version(warehouse_id) WHERE state = 'ACTIVE';

CREATE TABLE public.catalog_node (
  row_id uuid NOT NULL,
  node_id uuid NOT NULL,
  catalog_version_id uuid NOT NULL,
  code varchar(64) NOT NULL,
  node_type varchar(32) NOT NULL,
  name varchar(255) NOT NULL,
  active boolean NOT NULL,
  parent_node_id uuid,
  unit varchar(32),
  price_minor bigint,
  duration_minutes integer NOT NULL,
  include_in_estimate boolean NOT NULL,
  common_item boolean NOT NULL,
  show_in_main_menu boolean NOT NULL,
  photo_required boolean NOT NULL,
  routing_queue_id uuid,
  routing_queue_code varchar(64),
  routing_queue_kind varchar(64),
  opaque_references jsonb NOT NULL DEFAULT '[]',
  comment varchar(2000),
  media_references jsonb NOT NULL DEFAULT '[]',
  CONSTRAINT catalog_node_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_catalog_node_id UNIQUE (catalog_version_id, node_id),
  CONSTRAINT uk_catalog_node_code UNIQUE (catalog_version_id, code),
  CONSTRAINT fk_catalog_node_version FOREIGN KEY (catalog_version_id) REFERENCES public.catalog_version(id),
  CONSTRAINT fk_catalog_node_parent FOREIGN KEY (catalog_version_id, parent_node_id)
    REFERENCES public.catalog_node(catalog_version_id, node_id)
    DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT ck_catalog_node_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_catalog_node_type CHECK (node_type IN ('CATEGORY','SUBCATEGORY','WORK','MATERIAL','LOCATION','OPTION')),
  CONSTRAINT ck_catalog_node_name CHECK (length(btrim(name)) BETWEEN 1 AND 255),
  CONSTRAINT ck_catalog_node_price CHECK (price_minor IS NULL OR price_minor >= 0),
  CONSTRAINT ck_catalog_node_duration CHECK (duration_minutes BETWEEN 0 AND 525600),
  CONSTRAINT ck_catalog_node_routing CHECK (
    (routing_queue_id IS NULL AND routing_queue_code IS NULL AND routing_queue_kind IS NULL)
    OR (routing_queue_id IS NOT NULL AND length(btrim(routing_queue_code)) BETWEEN 1 AND 64
      AND length(btrim(routing_queue_kind)) BETWEEN 1 AND 64)),
  CONSTRAINT ck_catalog_node_references CHECK (jsonb_typeof(opaque_references) = 'array'),
  CONSTRAINT ck_catalog_node_media CHECK (jsonb_typeof(media_references) = 'array')
);

CREATE TABLE public.catalog_link (
  row_id uuid NOT NULL,
  link_id uuid NOT NULL,
  catalog_version_id uuid NOT NULL,
  source_node_id uuid NOT NULL,
  target_node_id uuid NOT NULL,
  link_type varchar(32) NOT NULL,
  sort_order integer NOT NULL DEFAULT 0,
  CONSTRAINT catalog_link_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_catalog_link_id UNIQUE (catalog_version_id, link_id),
  CONSTRAINT uk_catalog_link UNIQUE (catalog_version_id, source_node_id, target_node_id, link_type),
  CONSTRAINT fk_catalog_link_version FOREIGN KEY (catalog_version_id) REFERENCES public.catalog_version(id),
  CONSTRAINT fk_catalog_link_source FOREIGN KEY (catalog_version_id, source_node_id)
    REFERENCES public.catalog_node(catalog_version_id, node_id),
  CONSTRAINT fk_catalog_link_target FOREIGN KEY (catalog_version_id, target_node_id)
    REFERENCES public.catalog_node(catalog_version_id, node_id),
  CONSTRAINT ck_catalog_link_distinct CHECK (source_node_id <> target_node_id),
  CONSTRAINT ck_catalog_link_type CHECK (link_type IN ('DEPENDENCY','FOLLOW_UP')),
  CONSTRAINT ck_catalog_link_order CHECK (sort_order >= 0)
);

CREATE TABLE public.maintenance_estimate (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  rental_item_version_snapshot bigint NOT NULL,
  catalog_version_id uuid NOT NULL,
  state varchar(16) NOT NULL,
  revision integer NOT NULL DEFAULT 1,
  dispatch_date date,
  source_party varchar(512),
  comment varchar(4000),
  repair_id uuid,
  completed_at timestamptz,
  actor_ref jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT maintenance_estimate_pkey PRIMARY KEY (id),
  CONSTRAINT fk_estimate_catalog_version FOREIGN KEY (catalog_version_id) REFERENCES public.catalog_version(id),
  CONSTRAINT ck_estimate_version CHECK (version >= 0),
  CONSTRAINT ck_estimate_asset_version CHECK (rental_item_version_snapshot >= 0),
  CONSTRAINT ck_estimate_state CHECK (state IN ('DRAFT','COMPLETED')),
  CONSTRAINT ck_estimate_revision CHECK (revision >= 1),
  CONSTRAINT ck_estimate_actor_ref CHECK (jsonb_typeof(actor_ref) = 'object')
);
CREATE INDEX idx_estimate_warehouse_created ON public.maintenance_estimate(warehouse_id, created_at DESC, id);
CREATE INDEX idx_estimate_rental_item ON public.maintenance_estimate(rental_item_id, created_at DESC);

CREATE TABLE public.estimate_revision (
  id uuid NOT NULL,
  estimate_id uuid NOT NULL,
  revision integer NOT NULL,
  dispatch_date date NOT NULL,
  source_party varchar(512),
  amendment_reason varchar(2000),
  total_minor bigint NOT NULL,
  actor_ref jsonb NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT estimate_revision_pkey PRIMARY KEY (id),
  CONSTRAINT uk_estimate_revision UNIQUE (estimate_id, revision),
  CONSTRAINT fk_estimate_revision_estimate FOREIGN KEY (estimate_id) REFERENCES public.maintenance_estimate(id),
  CONSTRAINT ck_estimate_revision_number CHECK (revision >= 1),
  CONSTRAINT ck_estimate_revision_total CHECK (total_minor >= 0),
  CONSTRAINT ck_estimate_revision_actor CHECK (jsonb_typeof(actor_ref) = 'object')
);
CREATE INDEX idx_estimate_revision_history ON public.estimate_revision(estimate_id, revision);

CREATE TABLE public.estimate_line (
  row_id uuid NOT NULL,
  line_id uuid NOT NULL,
  estimate_id uuid NOT NULL,
  estimate_revision integer NOT NULL,
  line_no integer NOT NULL,
  catalog_node_id uuid,
  line_type varchar(24) NOT NULL,
  title varchar(1000) NOT NULL,
  quantity numeric(20,6) NOT NULL,
  unit_price_minor bigint NOT NULL,
  duration_minutes integer,
  queue_ref varchar(128),
  catalog_snapshot jsonb,
  comment varchar(2000),
  media_references jsonb NOT NULL DEFAULT '[]',
  CONSTRAINT estimate_line_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_estimate_line_id UNIQUE (estimate_id, estimate_revision, line_id),
  CONSTRAINT uk_estimate_line_number UNIQUE (estimate_id, estimate_revision, line_no),
  CONSTRAINT fk_estimate_line_revision FOREIGN KEY (estimate_id, estimate_revision)
    REFERENCES public.estimate_revision(estimate_id, revision) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT ck_estimate_line_revision CHECK (estimate_revision >= 1 AND line_no >= 0),
  CONSTRAINT ck_estimate_line_type CHECK (line_type IN ('WORK','MATERIAL')),
  CONSTRAINT ck_estimate_line_quantity CHECK (quantity > 0),
  CONSTRAINT ck_estimate_line_price CHECK (unit_price_minor >= 0),
  CONSTRAINT ck_estimate_line_duration CHECK (duration_minutes IS NULL OR duration_minutes >= 0),
  CONSTRAINT ck_estimate_line_catalog_snapshot CHECK (
    catalog_snapshot IS NULL OR jsonb_typeof(catalog_snapshot) = 'object'),
  CONSTRAINT ck_estimate_line_media CHECK (jsonb_typeof(media_references) = 'array')
);

CREATE TABLE public.estimate_plan_stage (
  row_id uuid NOT NULL,
  stage_id uuid NOT NULL,
  estimate_id uuid NOT NULL,
  estimate_revision integer NOT NULL,
  stage_no integer NOT NULL,
  stage_kind varchar(32) NOT NULL,
  routing_queue_id uuid NOT NULL,
  routing_queue_code varchar(64) NOT NULL,
  routing_queue_kind varchar(64) NOT NULL,
  task_deadline timestamptz,
  CONSTRAINT estimate_plan_stage_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_estimate_plan_stage_id UNIQUE (estimate_id, estimate_revision, stage_id),
  CONSTRAINT uk_estimate_plan_stage UNIQUE (estimate_id, estimate_revision, stage_no),
  CONSTRAINT fk_estimate_plan_revision FOREIGN KEY (estimate_id, estimate_revision)
    REFERENCES public.estimate_revision(estimate_id, revision) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT ck_estimate_plan_revision CHECK (estimate_revision >= 1 AND stage_no >= 0),
  CONSTRAINT ck_estimate_plan_kind CHECK (stage_kind IN ('REPAIR_WORK','MOVE_TO_REPAIR','MOVE_FROM_REPAIR')),
  CONSTRAINT ck_estimate_plan_routing CHECK (
    length(btrim(routing_queue_code)) BETWEEN 1 AND 64
    AND length(btrim(routing_queue_kind)) BETWEEN 1 AND 64)
);

CREATE TABLE public.maintenance_repair (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  rental_item_version_snapshot bigint NOT NULL,
  root_repair_id uuid,
  source_repair_id uuid,
  estimate_id uuid,
  origin varchar(24) NOT NULL,
  kind varchar(16) NOT NULL,
  execution_state varchar(24) NOT NULL,
  acceptance_state varchar(24) NOT NULL,
  dispatch_date date NOT NULL,
  source_party varchar(512),
  rework_reason varchar(2000),
  decision_reason varchar(2000),
  decision_actor_ref jsonb,
  decision_recorded_at timestamptz,
  actor_ref jsonb NOT NULL,
  external_task_id uuid NOT NULL,
  task_generation_state varchar(32) NOT NULL,
  delivery_state varchar(24) NOT NULL,
  delivery_attempts integer NOT NULL DEFAULT 0,
  delivery_updated_at timestamptz NOT NULL,
  task_board_version bigint,
  lease_id uuid,
  lease_version bigint,
  fencing_token bigint,
  lease_expires_at timestamptz,
  lease_reconciliation_state varchar(32) NOT NULL,
  reconciliation_state varchar(32) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT maintenance_repair_pkey PRIMARY KEY (id),
  CONSTRAINT uk_repair_external_task UNIQUE (external_task_id),
  CONSTRAINT fk_repair_root FOREIGN KEY (root_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT fk_repair_source FOREIGN KEY (source_repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT fk_repair_estimate FOREIGN KEY (estimate_id) REFERENCES public.maintenance_estimate(id),
  CONSTRAINT ck_repair_version CHECK (version >= 0),
  CONSTRAINT ck_repair_asset_version CHECK (rental_item_version_snapshot >= 0),
  CONSTRAINT ck_repair_origin CHECK (origin IN ('ESTIMATE','DIRECT_REPAIR')),
  CONSTRAINT ck_repair_kind CHECK (kind IN ('PRIMARY','REWORK')),
  CONSTRAINT ck_repair_execution CHECK (execution_state IN ('DRAFT','QUEUED','IN_PROGRESS','COMPLETED','CANCELLED')),
  CONSTRAINT ck_repair_acceptance CHECK (acceptance_state IN ('NOT_READY','PENDING','IN_REWORK','ACCEPTED','WRITTEN_OFF')),
  CONSTRAINT ck_repair_task_generation CHECK (task_generation_state IN ('PENDING_GENERATION','GENERATED','FAILED')),
  CONSTRAINT ck_repair_delivery CHECK (delivery_state IN ('PENDING','RETRY_PENDING','DELIVERED','QUARANTINED')),
  CONSTRAINT ck_repair_delivery_attempts CHECK (delivery_attempts >= 0),
  CONSTRAINT ck_repair_lease_reconciliation CHECK (lease_reconciliation_state IN ('NOT_REQUIRED','ACTIVE','RECONCILIATION_REQUIRED','RELEASED')),
  CONSTRAINT ck_repair_reconciliation CHECK (reconciliation_state IN ('NOT_REQUIRED','RECONCILIATION_REQUIRED','RECONCILED')),
  CONSTRAINT ck_repair_actor_ref CHECK (jsonb_typeof(actor_ref) = 'object'),
  CONSTRAINT ck_repair_decision_actor CHECK (decision_actor_ref IS NULL OR jsonb_typeof(decision_actor_ref) = 'object'),
  CONSTRAINT ck_repair_hierarchy CHECK (
    (kind = 'PRIMARY' AND source_repair_id IS NULL AND root_repair_id IS NULL)
    OR (kind = 'REWORK' AND source_repair_id IS NOT NULL AND root_repair_id IS NOT NULL
      AND source_repair_id <> id AND root_repair_id <> id)),
  CONSTRAINT ck_repair_origin_estimate CHECK (
    (kind = 'PRIMARY' AND origin = 'ESTIMATE' AND estimate_id IS NOT NULL)
    OR (kind = 'PRIMARY' AND origin = 'DIRECT_REPAIR' AND estimate_id IS NULL)
    OR (kind = 'REWORK' AND estimate_id IS NULL)),
  CONSTRAINT ck_repair_lease CHECK (
    (lease_id IS NULL AND lease_version IS NULL AND fencing_token IS NULL AND lease_expires_at IS NULL)
    OR (lease_id IS NOT NULL AND lease_version >= 0 AND fencing_token > 0 AND lease_expires_at IS NOT NULL))
);
ALTER TABLE public.maintenance_estimate
  ADD CONSTRAINT fk_estimate_repair FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id);
CREATE INDEX idx_repair_warehouse_created ON public.maintenance_repair(warehouse_id, created_at DESC, id);
CREATE INDEX idx_repair_rental_item ON public.maintenance_repair(rental_item_id, created_at DESC);
CREATE INDEX idx_repair_acceptance ON public.maintenance_repair(warehouse_id, acceptance_state, updated_at DESC);
CREATE UNIQUE INDEX uk_repair_primary_estimate
  ON public.maintenance_repair(estimate_id) WHERE estimate_id IS NOT NULL;
CREATE INDEX idx_repair_lease_renewal_due
  ON public.maintenance_repair(lease_expires_at, id)
  WHERE kind = 'PRIMARY'
    AND lease_reconciliation_state = 'ACTIVE'
    AND execution_state IN ('QUEUED','IN_PROGRESS','COMPLETED','CANCELLED')
    AND acceptance_state NOT IN ('ACCEPTED','WRITTEN_OFF');

CREATE TABLE public.repair_stage (
  row_id uuid NOT NULL,
  stage_id uuid NOT NULL,
  repair_id uuid NOT NULL,
  stage_no integer NOT NULL,
  stage_kind varchar(32) NOT NULL,
  state varchar(24) NOT NULL,
  routing_queue_id uuid NOT NULL,
  routing_queue_code varchar(64) NOT NULL,
  routing_queue_kind varchar(64) NOT NULL,
  external_queue_entry_id uuid,
  task_board_version bigint,
  task_generation_state varchar(32) NOT NULL,
  delivery_state varchar(24) NOT NULL,
  delivery_attempts integer NOT NULL DEFAULT 0,
  delivery_updated_at timestamptz NOT NULL,
  task_deadline timestamptz,
  completed_event_id uuid,
  completed_at timestamptz,
  CONSTRAINT repair_stage_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_repair_stage_id UNIQUE (repair_id, stage_id),
  CONSTRAINT uk_repair_stage_number UNIQUE (repair_id, stage_no),
  CONSTRAINT uk_repair_stage_external UNIQUE (external_queue_entry_id),
  CONSTRAINT fk_repair_stage_repair FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_repair_stage_number CHECK (stage_no >= 0),
  CONSTRAINT ck_repair_stage_kind CHECK (stage_kind IN ('REPAIR_WORK','MOVE_TO_REPAIR','MOVE_FROM_REPAIR')),
  CONSTRAINT ck_repair_stage_state CHECK (state IN ('PLANNED','QUEUED','IN_PROGRESS','DONE','CANCELLED')),
  CONSTRAINT ck_repair_stage_generation CHECK (task_generation_state IN ('PENDING_GENERATION','GENERATED','FAILED')),
  CONSTRAINT ck_repair_stage_delivery CHECK (delivery_state IN ('PENDING','RETRY_PENDING','DELIVERED','QUARANTINED')),
  CONSTRAINT ck_repair_stage_attempts CHECK (delivery_attempts >= 0),
  CONSTRAINT ck_repair_stage_routing CHECK (
    length(btrim(routing_queue_code)) BETWEEN 1 AND 64
    AND length(btrim(routing_queue_kind)) BETWEEN 1 AND 64),
  CONSTRAINT ck_repair_stage_completion CHECK ((state = 'DONE') = (completed_at IS NOT NULL))
);

CREATE TABLE public.maintenance_media_reference (
  aggregate_type varchar(32) NOT NULL,
  aggregate_id uuid NOT NULL,
  media_id uuid NOT NULL,
  generation bigint NOT NULL,
  owner_type varchar(64) NOT NULL,
  warehouse_id uuid NOT NULL,
  safe_metadata jsonb NOT NULL DEFAULT '{}',
  attached_at timestamptz NOT NULL,
  CONSTRAINT maintenance_media_reference_pkey PRIMARY KEY (aggregate_type, aggregate_id, media_id),
  CONSTRAINT ck_maintenance_media_generation CHECK (generation >= 0),
  CONSTRAINT ck_maintenance_media_owner CHECK (owner_type IN (
    'MAINTENANCE_ESTIMATE','MAINTENANCE_REPAIR','MAINTENANCE_ACCEPTANCE','MAINTENANCE_CATALOG_NODE')),
  CONSTRAINT ck_maintenance_media_metadata CHECK (jsonb_typeof(safe_metadata) = 'object')
);

CREATE TABLE public.media_fact_projection (
  media_id uuid NOT NULL,
  generation bigint NOT NULL,
  owner_type varchar(64) NOT NULL,
  owner_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  media_status varchar(16) NOT NULL,
  safe_metadata jsonb NOT NULL DEFAULT '{}',
  aggregate_version bigint NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT media_fact_projection_pkey PRIMARY KEY (media_id),
  CONSTRAINT ck_media_fact_generation CHECK (generation >= 0 AND aggregate_version >= 0),
  CONSTRAINT ck_media_fact_status CHECK (media_status IN ('PENDING','READY','FAILED','DELETED')),
  CONSTRAINT ck_media_fact_metadata CHECK (jsonb_typeof(safe_metadata) = 'object')
);

CREATE TABLE public.integration_reconciliation (
  id uuid NOT NULL,
  repair_id uuid,
  dependency_type varchar(32) NOT NULL,
  operation_type varchar(64) NOT NULL,
  idempotency_key uuid NOT NULL,
  state varchar(32) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  last_error_code varchar(64),
  response_snapshot jsonb,
  review_version bigint NOT NULL DEFAULT 0,
  review_subject_id uuid,
  review_reason varchar(2000),
  reviewed_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT integration_reconciliation_pkey PRIMARY KEY (id),
  CONSTRAINT uk_integration_reconciliation_key UNIQUE (dependency_type, operation_type, idempotency_key),
  CONSTRAINT fk_reconciliation_repair FOREIGN KEY (repair_id) REFERENCES public.maintenance_repair(id),
  CONSTRAINT ck_reconciliation_dependency CHECK (dependency_type IN ('ASSET','TASK_BOARD')),
  CONSTRAINT ck_reconciliation_state CHECK (state IN ('PENDING','RETRY_PENDING','CONFIRMED','RECONCILIATION_REQUIRED','QUARANTINED')),
  CONSTRAINT ck_reconciliation_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_reconciliation_snapshot CHECK (response_snapshot IS NULL OR jsonb_typeof(response_snapshot) = 'object'),
  CONSTRAINT ck_reconciliation_review_version CHECK (review_version >= 0),
  CONSTRAINT ck_reconciliation_review CHECK (
    (review_version = 0 AND review_subject_id IS NULL AND review_reason IS NULL AND reviewed_at IS NULL)
    OR (review_version > 0 AND review_subject_id IS NOT NULL AND review_reason IS NOT NULL
      AND length(btrim(review_reason)) BETWEEN 1 AND 2000 AND reviewed_at IS NOT NULL))
);
CREATE INDEX idx_integration_reconciliation_due
  ON public.integration_reconciliation(next_attempt_at, id)
  WHERE state IN ('PENDING','RETRY_PENDING','RECONCILIATION_REQUIRED') AND attempt_count < 4;

CREATE TABLE public.event_stream_head (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  current_version bigint NOT NULL,
  last_event_id uuid NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT event_stream_head_pkey PRIMARY KEY (aggregate_type, aggregate_id),
  CONSTRAINT ck_maintenance_stream_type CHECK (aggregate_type IN ('CATALOG_VERSION','ESTIMATE','REPAIR')),
  CONSTRAINT ck_maintenance_stream_version CHECK (current_version >= 0)
);

CREATE TABLE public.domain_event (
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  event_version integer NOT NULL,
  occurred_at timestamptz,
  recorded_at timestamptz NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  actor_ref jsonb,
  payload jsonb NOT NULL,
  payload_sha256 char(64) NOT NULL,
  baseline boolean NOT NULL DEFAULT false,
  CONSTRAINT domain_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_maintenance_event_stream_version UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT uk_maintenance_event_identity UNIQUE (event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT fk_maintenance_event_stream FOREIGN KEY (aggregate_type, aggregate_id)
    REFERENCES public.event_stream_head(aggregate_type, aggregate_id),
  CONSTRAINT ck_maintenance_event_type CHECK (event_type IN (
    'maintenance.catalog-version.imported.v1','maintenance.catalog-version.changed.v1',
    'maintenance.catalog-version.activated.v1','maintenance.catalog-version.superseded.v1',
    'maintenance.estimate.created.v1','maintenance.estimate.draft-changed.v1',
    'maintenance.estimate.completed.v1','maintenance.estimate.amended.v1',
    'maintenance.repair.created.v1','maintenance.repair.plan-changed.v1',
    'maintenance.repair.queued.v1','maintenance.repair.stage-completed.v1',
    'maintenance.repair.pending-acceptance.v1','maintenance.repair.rework-created.v1',
    'maintenance.repair.accepted.v1','maintenance.repair.written-off.v1')),
  CONSTRAINT ck_maintenance_event_version CHECK (aggregate_version >= 0 AND event_version = 1),
  CONSTRAINT ck_maintenance_event_payload CHECK (jsonb_typeof(payload) = 'object' AND payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_event_actor CHECK (actor_ref IS NULL OR jsonb_typeof(actor_ref) = 'object'),
  CONSTRAINT ck_maintenance_event_baseline CHECK (
    (NOT baseline AND occurred_at IS NOT NULL) OR (baseline AND occurred_at IS NULL))
);
CREATE INDEX idx_maintenance_domain_event_stream
  ON public.domain_event(aggregate_type, aggregate_id, aggregate_version);

CREATE TABLE public.aggregate_snapshot (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  state jsonb NOT NULL,
  state_sha256 char(64) NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT aggregate_snapshot_pkey PRIMARY KEY (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_maintenance_snapshot_stream FOREIGN KEY (aggregate_type, aggregate_id)
    REFERENCES public.event_stream_head(aggregate_type, aggregate_id),
  CONSTRAINT ck_maintenance_snapshot_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_maintenance_snapshot_state CHECK (jsonb_typeof(state) = 'object' AND state_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.projection_checkpoint (
  projection_name varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  projection_sha256 char(64) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projection_name, aggregate_type, aggregate_id),
  CONSTRAINT ck_maintenance_projection_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_maintenance_projection_hash CHECK (projection_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.outbox_event (
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  topic varchar(192) NOT NULL,
  envelope_body jsonb NOT NULL,
  envelope_sha256 char(64) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_owner varchar(128),
  lease_token uuid,
  lease_until timestamptz,
  published_at timestamptz,
  dlt_at timestamptz,
  last_error_code varchar(64),
  review_version bigint NOT NULL DEFAULT 0,
  review_subject_id uuid,
  review_reason varchar(2000),
  reviewed_at timestamptz,
  created_at timestamptz NOT NULL,
  CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_maintenance_outbox_stream_version UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_maintenance_outbox_event FOREIGN KEY (event_id, aggregate_type, aggregate_id, aggregate_version, event_type)
    REFERENCES public.domain_event(event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_maintenance_outbox_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED','DLT','QUARANTINED')),
  CONSTRAINT ck_maintenance_outbox_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_maintenance_outbox_review_version CHECK (review_version >= 0),
  CONSTRAINT ck_maintenance_outbox_review CHECK (
    (review_version = 0 AND review_subject_id IS NULL AND review_reason IS NULL AND reviewed_at IS NULL)
    OR (review_version > 0 AND review_subject_id IS NOT NULL AND review_reason IS NOT NULL
      AND length(btrim(review_reason)) BETWEEN 1 AND 2000 AND reviewed_at IS NOT NULL)),
  CONSTRAINT ck_maintenance_outbox_body CHECK (jsonb_typeof(envelope_body) = 'object' AND envelope_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_outbox_topic CHECK (topic IN (
    'rwms.maintenance.catalog-version.v1','rwms.maintenance.estimate.v1','rwms.maintenance.repair.v1')),
  CONSTRAINT ck_maintenance_outbox_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_maintenance_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT ck_maintenance_outbox_dlt CHECK ((status = 'DLT') = (dlt_at IS NOT NULL))
);
CREATE INDEX idx_maintenance_outbox_pending
  ON public.outbox_event(next_attempt_at, created_at, event_id) WHERE status = 'PENDING';
CREATE INDEX idx_maintenance_outbox_reclaim
  ON public.outbox_event(lease_until, created_at, event_id) WHERE status = 'IN_FLIGHT';

CREATE TABLE public.inbox_message (
  consumer_group varchar(128) NOT NULL,
  event_id uuid NOT NULL,
  source_topic varchar(192) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  payload_sha256 char(64) NOT NULL,
  envelope_body jsonb NOT NULL,
  status varchar(24) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  received_at timestamptz NOT NULL,
  processed_at timestamptz,
  next_attempt_at timestamptz,
  dlt_at timestamptz,
  quarantine_reason varchar(128),
  CONSTRAINT inbox_message_pkey PRIMARY KEY (consumer_group, event_id),
  CONSTRAINT ck_maintenance_inbox_status CHECK (status IN ('RECEIVED','PROCESSED','RETRY','DLT','QUARANTINED')),
  CONSTRAINT ck_maintenance_inbox_version CHECK (aggregate_version >= 0 AND attempt_count >= 0),
  CONSTRAINT ck_maintenance_inbox_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_inbox_body CHECK (jsonb_typeof(envelope_body) = 'object'),
  CONSTRAINT ck_maintenance_inbox_topic CHECK (source_topic IN (
    'rwms.task-board.board-task.v1','rwms.task-board.queue-entry.v1','rwms.media.media.v1',
    'rwms.asset.rental-item.v1','rwms.asset.operation-lease.v1'))
);
CREATE INDEX idx_maintenance_inbox_retry
  ON public.inbox_message(consumer_group, next_attempt_at, received_at)
  WHERE status = 'RETRY';

CREATE TABLE public.consumer_aggregate_checkpoint (
  consumer_group varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  last_event_id uuid,
  last_aggregate_version bigint NOT NULL,
  blocked boolean NOT NULL DEFAULT false,
  quarantine_reason varchar(128),
  updated_at timestamptz NOT NULL,
  CONSTRAINT consumer_aggregate_checkpoint_pkey PRIMARY KEY (consumer_group, aggregate_type, aggregate_id),
  CONSTRAINT ck_maintenance_consumer_checkpoint CHECK (
    (last_aggregate_version = -1 AND last_event_id IS NULL) OR (last_aggregate_version >= 0 AND last_event_id IS NOT NULL)),
  CONSTRAINT ck_maintenance_consumer_blocked CHECK (
    (blocked AND quarantine_reason IS NOT NULL) OR (NOT blocked AND quarantine_reason IS NULL))
);

CREATE TABLE public.version_gap_quarantine (
  quarantine_id uuid NOT NULL,
  consumer_group varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  expected_version bigint NOT NULL,
  received_version bigint NOT NULL,
  received_event_id uuid NOT NULL,
  payload_sha256 char(64) NOT NULL,
  reason_code varchar(128) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'OPEN',
  detected_at timestamptz NOT NULL,
  resolved_at timestamptz,
  resolution_reason varchar(500),
  resolved_by_subject_id uuid,
  CONSTRAINT version_gap_quarantine_pkey PRIMARY KEY (quarantine_id),
  CONSTRAINT uk_maintenance_version_gap_event UNIQUE (consumer_group, received_event_id),
  CONSTRAINT ck_maintenance_version_gap_versions CHECK (expected_version >= 0 AND received_version > expected_version),
  CONSTRAINT ck_maintenance_version_gap_status CHECK (status IN ('OPEN','RESOLVED')),
  CONSTRAINT ck_maintenance_version_gap_reason CHECK (reason_code = 'AGGREGATE_VERSION_GAP')
);

CREATE TABLE public.sanitized_dead_letter (
  dlt_id uuid NOT NULL,
  destination varchar(224) NOT NULL,
  source_topic varchar(192),
  source_event_id uuid,
  message_sha256 char(64) NOT NULL,
  failure_code varchar(64) NOT NULL,
  safe_body jsonb NOT NULL,
  body_sha256 char(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_owner varchar(128),
  lease_token uuid,
  lease_until timestamptz,
  created_at timestamptz NOT NULL,
  published_at timestamptz,
  last_error_code varchar(64),
  replay_status varchar(24) NOT NULL DEFAULT 'NOT_REPLAYABLE',
  review_version bigint NOT NULL DEFAULT 0,
  reviewed_at timestamptz,
  reviewed_by_subject_id uuid,
  replayed_at timestamptz,
  CONSTRAINT sanitized_dead_letter_pkey PRIMARY KEY (dlt_id),
  CONSTRAINT ck_maintenance_dlt_failure CHECK (failure_code IN (
    'VALIDATION_REJECTED','PROCESSING_FAILED','VERSION_GAP','EVENT_ID_CONFLICT')),
  CONSTRAINT ck_maintenance_dlt_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED','FAILED')),
  CONSTRAINT ck_maintenance_dlt_hashes CHECK (message_sha256 ~ '^[0-9a-f]{64}$' AND body_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_dlt_body CHECK (
    jsonb_typeof(safe_body) = 'object'
    AND jsonb_exists_all(safe_body, array['failureCode','messageSha256','recordedAt'])
    AND safe_body - array['failureCode','messageSha256','recordedAt'] = '{}'::jsonb),
  CONSTRAINT ck_maintenance_dlt_attempts CHECK (attempt_count >= 0 AND review_version >= 0),
  CONSTRAINT ck_maintenance_dlt_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_maintenance_dlt_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT ck_maintenance_dlt_replay_status CHECK (
    replay_status IN ('NOT_REPLAYABLE','AWAITING_REVIEW','APPROVED','REJECTED','REPLAYED')),
  CONSTRAINT ck_maintenance_dlt_replay_source CHECK (
    (source_event_id IS NULL AND replay_status = 'NOT_REPLAYABLE')
    OR (source_event_id IS NOT NULL AND source_topic IS NOT NULL AND replay_status <> 'NOT_REPLAYABLE')),
  CONSTRAINT ck_maintenance_dlt_review CHECK (
    (replay_status IN ('NOT_REPLAYABLE','AWAITING_REVIEW') AND reviewed_at IS NULL AND reviewed_by_subject_id IS NULL)
    OR (replay_status IN ('APPROVED','REJECTED','REPLAYED') AND reviewed_at IS NOT NULL AND reviewed_by_subject_id IS NOT NULL)),
  CONSTRAINT ck_maintenance_dlt_replayed CHECK ((replay_status = 'REPLAYED') = (replayed_at IS NOT NULL))
);
CREATE INDEX idx_maintenance_dlt_claim
  ON public.sanitized_dead_letter(next_attempt_at, created_at, dlt_id) WHERE status = 'PENDING';
CREATE INDEX idx_maintenance_dlt_reclaim
  ON public.sanitized_dead_letter(lease_until, created_at, dlt_id) WHERE status = 'IN_FLIGHT';

CREATE TABLE public.maintenance_inbound_replay_message (
  event_id uuid NOT NULL,
  source_topic varchar(192) NOT NULL,
  kafka_key varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type varchar(160) NOT NULL,
  envelope_body jsonb NOT NULL,
  message_sha256 char(64) NOT NULL,
  state varchar(24) NOT NULL DEFAULT 'STAGED',
  staged_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT maintenance_inbound_replay_message_pkey PRIMARY KEY (event_id),
  CONSTRAINT ck_maintenance_replay_topic CHECK (source_topic IN (
    'rwms.task-board.board-task.v1','rwms.task-board.queue-entry.v1','rwms.media.media.v1',
    'rwms.asset.rental-item.v1','rwms.asset.operation-lease.v1')),
  CONSTRAINT ck_maintenance_replay_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_maintenance_replay_body CHECK (
    jsonb_typeof(envelope_body) = 'object' AND message_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_replay_state CHECK (
    state IN ('STAGED','APPLIED','DLT','REPLAY_APPROVED','REJECTED'))
);

CREATE TABLE public.maintenance_inbound_correlation (
  source_event_id uuid NOT NULL,
  source_topic varchar(192) NOT NULL,
  board_task_id uuid,
  external_task_id uuid,
  queue_entry_id uuid,
  counterpart_event_id uuid,
  state varchar(16) NOT NULL DEFAULT 'PENDING',
  recorded_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT maintenance_inbound_correlation_pkey PRIMARY KEY (source_event_id),
  CONSTRAINT fk_maintenance_inbound_correlation_replay FOREIGN KEY (source_event_id)
    REFERENCES public.maintenance_inbound_replay_message(event_id),
  CONSTRAINT fk_maintenance_inbound_correlation_counterpart FOREIGN KEY (counterpart_event_id)
    REFERENCES public.maintenance_inbound_replay_message(event_id),
  CONSTRAINT ck_maintenance_inbound_correlation_topic CHECK (source_topic IN (
    'rwms.task-board.board-task.v1','rwms.task-board.queue-entry.v1')),
  CONSTRAINT ck_maintenance_inbound_correlation_identity CHECK (
    board_task_id IS NOT NULL AND (
      (source_topic = 'rwms.task-board.board-task.v1'
        AND external_task_id IS NOT NULL AND queue_entry_id IS NULL)
      OR (source_topic = 'rwms.task-board.queue-entry.v1'
        AND external_task_id IS NULL AND queue_entry_id IS NOT NULL))),
  CONSTRAINT ck_maintenance_inbound_correlation_state CHECK (state IN ('PENDING','CORRELATED','APPLIED')),
  CONSTRAINT ck_maintenance_inbound_correlation_not_self CHECK (
    counterpart_event_id IS NULL OR counterpart_event_id <> source_event_id),
  CONSTRAINT ck_maintenance_inbound_correlation_counterpart CHECK (
    (state = 'PENDING' AND counterpart_event_id IS NULL)
    OR (state IN ('CORRELATED','APPLIED') AND counterpart_event_id IS NOT NULL))
);
CREATE INDEX idx_maintenance_inbound_correlation_board_task
  ON public.maintenance_inbound_correlation(board_task_id, source_event_id);
CREATE INDEX idx_maintenance_inbound_correlation_external_task
  ON public.maintenance_inbound_correlation(external_task_id, source_event_id)
  WHERE external_task_id IS NOT NULL;
CREATE INDEX idx_maintenance_inbound_correlation_pending_queue
  ON public.maintenance_inbound_correlation(board_task_id, source_event_id)
  WHERE state = 'PENDING' AND queue_entry_id IS NOT NULL;

CREATE TABLE public.rental_item_fact_projection (
  rental_item_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  asset_status varchar(64) NOT NULL,
  aggregate_version bigint NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT rental_item_fact_projection_pkey PRIMARY KEY (rental_item_id),
  CONSTRAINT ck_maintenance_rental_fact_version CHECK (aggregate_version >= 0)
);

CREATE TABLE public.operation_lease_fact_projection (
  lease_id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  fencing_token bigint NOT NULL,
  lease_state varchar(16) NOT NULL,
  aggregate_version bigint NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT operation_lease_fact_projection_pkey PRIMARY KEY (lease_id),
  CONSTRAINT ck_maintenance_lease_fact_fence CHECK (fencing_token >= 1),
  CONSTRAINT ck_maintenance_lease_fact_state CHECK (lease_state IN ('ACTIVE','RELEASED','EXPIRED')),
  CONSTRAINT ck_maintenance_lease_fact_version CHECK (aggregate_version >= 0)
);

CREATE TABLE public.maintenance_idempotency_record (
  subject_id uuid NOT NULL,
  command_scope varchar(96) NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 char(64) NOT NULL,
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT maintenance_idempotency_record_pkey PRIMARY KEY (subject_id, command_scope, idempotency_key),
  CONSTRAINT ck_maintenance_idempotency_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_maintenance_idempotency_status CHECK (response_status BETWEEN 200 AND 299),
  CONSTRAINT ck_maintenance_idempotency_body CHECK (jsonb_typeof(response_body) IN ('object','array')),
  CONSTRAINT ck_maintenance_idempotency_expiry CHECK (expires_at > created_at)
);
CREATE INDEX idx_maintenance_idempotency_expiry ON public.maintenance_idempotency_record(expires_at);
