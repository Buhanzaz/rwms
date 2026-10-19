-- RWMS inventory-service clean cumulative V1 schema.
-- Legacy HSQLDB and browser LocalStorage/IndexedDB are evidence only and are
-- intentionally not imported or seeded by this migration.

CREATE TABLE public.inventory_session (
  id uuid NOT NULL,
  session_revision bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  warehouse_version_snapshot bigint NOT NULL,
  warehouse_time_zone varchar(64) NOT NULL,
  business_date date NOT NULL,
  lifecycle varchar(16) NOT NULL,
  start_operation_id uuid NOT NULL,
  start_idempotency_key uuid NOT NULL,
  start_request_sha256 varchar(64) NOT NULL,
  expected_population_count integer NOT NULL,
  expected_population_sha256 varchar(64) NOT NULL,
  started_by_subject_id uuid NOT NULL,
  started_actor_ref jsonb NOT NULL,
  started_at timestamptz NOT NULL,
  completion_validation_sha256 varchar(64),
  completion_acknowledgement_sha256 varchar(64),
  validated_at timestamptz,
  completed_by_actor_ref jsonb,
  completed_at timestamptz,
  cancelled_by_actor_ref jsonb,
  cancellation_reason varchar(2000),
  cancelled_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_session_pkey PRIMARY KEY (id),
  CONSTRAINT uk_inventory_start_operation UNIQUE (start_operation_id),
  CONSTRAINT uk_inventory_session_identity UNIQUE (warehouse_id, id),
  CONSTRAINT ck_inventory_session_revision CHECK (session_revision >= 0),
  CONSTRAINT ck_inventory_warehouse_version CHECK (warehouse_version_snapshot >= 0),
  CONSTRAINT ck_inventory_time_zone CHECK (length(btrim(warehouse_time_zone)) BETWEEN 1 AND 64),
  CONSTRAINT ck_inventory_lifecycle CHECK (lifecycle IN ('ACTIVE','COMPLETED','CANCELLED')),
  CONSTRAINT ck_inventory_start_hashes CHECK (
    start_request_sha256 ~ '^[0-9a-f]{64}$'
    AND expected_population_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_expected_count CHECK (expected_population_count >= 0),
  CONSTRAINT ck_inventory_started_actor CHECK (
    jsonb_typeof(started_actor_ref) = 'object'
    AND jsonb_exists_all(started_actor_ref, array['subjectId','principalType','profileRevision'])
    AND started_actor_ref - array['subjectId','principalType','profileRevision'] = '{}'::jsonb),
  CONSTRAINT ck_inventory_terminal_actor CHECK (
    (completed_by_actor_ref IS NULL OR jsonb_typeof(completed_by_actor_ref) = 'object')
    AND (cancelled_by_actor_ref IS NULL OR jsonb_typeof(cancelled_by_actor_ref) = 'object')),
  CONSTRAINT ck_inventory_terminal_state CHECK (
    (lifecycle = 'ACTIVE'
      AND completion_validation_sha256 IS NULL
      AND completion_acknowledgement_sha256 IS NULL
      AND validated_at IS NULL AND completed_by_actor_ref IS NULL AND completed_at IS NULL
      AND cancelled_by_actor_ref IS NULL AND cancellation_reason IS NULL AND cancelled_at IS NULL)
    OR (lifecycle = 'COMPLETED'
      AND completion_validation_sha256 ~ '^[0-9a-f]{64}$'
      AND completion_acknowledgement_sha256 ~ '^[0-9a-f]{64}$'
      AND validated_at IS NOT NULL AND completed_by_actor_ref IS NOT NULL AND completed_at IS NOT NULL
      AND cancelled_by_actor_ref IS NULL AND cancellation_reason IS NULL AND cancelled_at IS NULL)
    OR (lifecycle = 'CANCELLED'
      AND completion_validation_sha256 IS NULL
      AND completion_acknowledgement_sha256 IS NULL
      AND validated_at IS NULL AND completed_by_actor_ref IS NULL AND completed_at IS NULL
      AND cancelled_by_actor_ref IS NOT NULL
      AND length(btrim(cancellation_reason)) BETWEEN 1 AND 2000
      AND cancelled_at IS NOT NULL)),
  CONSTRAINT ck_inventory_session_times CHECK (
    updated_at >= created_at AND started_at >= created_at
    AND (validated_at IS NULL OR validated_at >= started_at)
    AND (completed_at IS NULL OR completed_at >= validated_at)
    AND (cancelled_at IS NULL OR cancelled_at >= started_at))
);
CREATE UNIQUE INDEX uk_inventory_one_active_session
  ON public.inventory_session(warehouse_id) WHERE lifecycle = 'ACTIVE';
CREATE INDEX idx_inventory_session_history
  ON public.inventory_session(warehouse_id, started_at DESC, id);
CREATE INDEX idx_inventory_session_business_date
  ON public.inventory_session(warehouse_id, business_date DESC, id);

CREATE TABLE public.inventory_start_operation (
  operation_id uuid NOT NULL,
  subject_id uuid NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  warehouse_id uuid NOT NULL,
  state varchar(24) NOT NULL,
  session_id uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT inventory_start_operation_pkey PRIMARY KEY (operation_id),
  CONSTRAINT uk_inventory_start_operation_key UNIQUE (subject_id, idempotency_key),
  CONSTRAINT uk_inventory_start_operation_session UNIQUE (session_id),
  CONSTRAINT fk_inventory_start_operation_session FOREIGN KEY (session_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT ck_inventory_start_operation_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_start_operation_state CHECK (state IN (
    'REQUESTED','CAPTURED','SESSION_COMMITTED','RELEASE_PENDING','RELEASED','FAILED')),
  CONSTRAINT ck_inventory_start_operation_link CHECK (
    (state IN ('REQUESTED','CAPTURED','FAILED') AND session_id IS NULL)
    OR (state = 'SESSION_COMMITTED' AND session_id IS NOT NULL)
    OR state IN ('RELEASE_PENDING','RELEASED')),
  CONSTRAINT ck_inventory_start_operation_times CHECK (
    updated_at >= created_at AND expires_at = created_at + interval '7 days')
);
CREATE INDEX idx_inventory_start_operation_expiry
  ON public.inventory_start_operation(expires_at);

CREATE TABLE public.inventory_start_capture_attempt (
  operation_id uuid NOT NULL,
  technical_attempt bigint NOT NULL,
  request_fingerprint varchar(64) NOT NULL,
  requested_at timestamptz NOT NULL,
  CONSTRAINT inventory_start_capture_attempt_pkey PRIMARY KEY (operation_id, technical_attempt),
  CONSTRAINT fk_inventory_start_capture_attempt_operation FOREIGN KEY (operation_id)
    REFERENCES public.inventory_start_operation(operation_id),
  CONSTRAINT ck_inventory_start_capture_attempt_no CHECK (technical_attempt >= 1),
  CONSTRAINT ck_inventory_start_capture_attempt_hash CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.inventory_start_capture_result (
  operation_id uuid NOT NULL,
  technical_attempt bigint NOT NULL,
  outcome varchar(24) NOT NULL,
  capture_id uuid,
  membership_digest varchar(64),
  total_count integer,
  expires_at timestamptz,
  failure_code varchar(64),
  recorded_at timestamptz NOT NULL,
  CONSTRAINT inventory_start_capture_result_pkey PRIMARY KEY (operation_id, technical_attempt),
  CONSTRAINT uk_inventory_start_capture_id UNIQUE (capture_id),
  CONSTRAINT uk_inventory_start_capture_result_identity UNIQUE (
    operation_id, technical_attempt, capture_id),
  CONSTRAINT fk_inventory_start_capture_result_attempt FOREIGN KEY (operation_id, technical_attempt)
    REFERENCES public.inventory_start_capture_attempt(operation_id, technical_attempt),
  CONSTRAINT ck_inventory_start_capture_result_outcome CHECK (
    outcome IN ('CAPTURED','TRANSIENT_FAILED','REJECTED')),
  CONSTRAINT ck_inventory_start_capture_result_shape CHECK (
    (outcome = 'CAPTURED' AND capture_id IS NOT NULL
      AND membership_digest ~ '^[0-9a-f]{64}$' AND total_count >= 0
      AND expires_at > recorded_at AND failure_code IS NULL)
    OR (outcome IN ('TRANSIENT_FAILED','REJECTED') AND capture_id IS NULL
      AND membership_digest IS NULL AND total_count IS NULL AND expires_at IS NULL
      AND length(btrim(failure_code)) BETWEEN 1 AND 64))
);

CREATE TABLE public.inventory_capture_release (
  operation_id uuid NOT NULL,
  technical_attempt bigint NOT NULL,
  capture_id uuid NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'PENDING',
  attempt_count integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL,
  lease_owner varchar(128),
  lease_token uuid,
  lease_until timestamptz,
  last_failure_code varchar(64),
  created_at timestamptz NOT NULL,
  released_at timestamptz,
  CONSTRAINT inventory_capture_release_pkey PRIMARY KEY (operation_id),
  CONSTRAINT uk_inventory_capture_release_id UNIQUE (capture_id),
  CONSTRAINT fk_inventory_capture_release_result FOREIGN KEY (
    operation_id, technical_attempt, capture_id)
    REFERENCES public.inventory_start_capture_result(
      operation_id, technical_attempt, capture_id),
  CONSTRAINT ck_inventory_capture_release_state CHECK (
    state IN ('HELD','PENDING','IN_FLIGHT','RELEASED')),
  CONSTRAINT ck_inventory_capture_release_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_capture_release_lease CHECK (
    (state = 'IN_FLIGHT' AND lease_owner IS NOT NULL
      AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (state <> 'IN_FLIGHT' AND lease_owner IS NULL
      AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_inventory_capture_release_result CHECK (
    (state = 'RELEASED' AND released_at IS NOT NULL)
    OR (state <> 'RELEASED' AND released_at IS NULL))
);
CREATE INDEX idx_inventory_capture_release_pending
  ON public.inventory_capture_release(next_attempt_at, created_at, operation_id)
  WHERE state = 'PENDING';
CREATE INDEX idx_inventory_capture_release_reclaim
  ON public.inventory_capture_release(lease_until, created_at, operation_id)
  WHERE state = 'IN_FLIGHT';

CREATE TABLE public.inventory_expected_item (
  row_id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  item_order integer NOT NULL,
  asset_id uuid NOT NULL,
  asset_version_snapshot bigint NOT NULL,
  asset_status_snapshot varchar(48) NOT NULL,
  display_canonical_number varchar(128) NOT NULL,
  identity_match_key varchar(128) NOT NULL,
  safe_passport_snapshot jsonb NOT NULL,
  safe_contents_snapshot jsonb NOT NULL,
  captured_at timestamptz NOT NULL,
  CONSTRAINT inventory_expected_item_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_expected_item_row_identity UNIQUE (inventory_id, row_id),
  CONSTRAINT uk_expected_item_asset UNIQUE (inventory_id, asset_id),
  CONSTRAINT uk_expected_item_match_key UNIQUE (inventory_id, identity_match_key),
  CONSTRAINT uk_expected_item_order UNIQUE (inventory_id, item_order),
  CONSTRAINT uk_expected_item_finding UNIQUE (inventory_id, finding_id),
  CONSTRAINT fk_expected_item_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT ck_expected_item_order CHECK (item_order >= 0),
  CONSTRAINT ck_expected_item_version CHECK (asset_version_snapshot >= 0),
  CONSTRAINT ck_expected_item_status CHECK (asset_status_snapshot IN (
    'NEW','BOOKED','REPAIR','WAITING_REPAIR_CHECK','CAPITAL_REPAIR','AFTER_RENT',
    'SALE','USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS')),
  CONSTRAINT ck_expected_item_number CHECK (
    length(btrim(display_canonical_number)) BETWEEN 1 AND 128
    AND length(btrim(identity_match_key)) BETWEEN 1 AND 128),
  CONSTRAINT ck_expected_item_snapshots CHECK (
    jsonb_typeof(safe_passport_snapshot) = 'object'
    AND jsonb_typeof(safe_contents_snapshot) IN ('object','array'))
);

CREATE TABLE public.inventory_finding (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  expected_item_id uuid,
  finding_revision bigint NOT NULL DEFAULT 0,
  owner_proof_revision bigint NOT NULL DEFAULT 0,
  owner_proof_active boolean NOT NULL DEFAULT true,
  origin varchar(32) NOT NULL,
  inspection varchar(24) NOT NULL,
  reconciliation varchar(24) NOT NULL,
  asset_id uuid,
  asset_version_snapshot bigint,
  display_canonical_number varchar(128) NOT NULL,
  identity_match_key varchar(128) NOT NULL,
  passport_observation_state varchar(24) NOT NULL DEFAULT 'ABSENT',
  passport_observation jsonb,
  equipment_observation_state varchar(24) NOT NULL DEFAULT 'ABSENT',
  equipment_observation jsonb,
  mutation_state varchar(32) NOT NULL DEFAULT 'IDLE',
  maintenance_plan_fingerprint_sha256 varchar(64),
  actor_ref jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_finding_pkey PRIMARY KEY (id),
  CONSTRAINT uk_inventory_finding_identity UNIQUE (inventory_id, id),
  CONSTRAINT uk_finding_expected_item UNIQUE (inventory_id, expected_item_id),
  CONSTRAINT fk_finding_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT fk_finding_expected_item FOREIGN KEY (inventory_id, expected_item_id)
    REFERENCES public.inventory_expected_item(inventory_id, row_id)
    DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT ck_finding_revision CHECK (finding_revision >= 0),
  CONSTRAINT ck_finding_owner_proof_revision CHECK (owner_proof_revision >= 0),
  CONSTRAINT ck_finding_origin CHECK (
    origin IN ('EXPECTED','ADDED_NEW','ADDED_USED','UNEXPECTED_EXISTING')),
  CONSTRAINT ck_finding_inspection CHECK (
    inspection IN ('NOT_INSPECTED','READY','WORK_STAGED')),
  CONSTRAINT ck_finding_reconciliation CHECK (
    reconciliation IN ('MATCHED','MISSING','CONFLICT')),
  CONSTRAINT ck_finding_expected_link CHECK ((origin = 'EXPECTED') = (expected_item_id IS NOT NULL)),
  CONSTRAINT ck_finding_asset_snapshot CHECK (
    (asset_id IS NULL AND asset_version_snapshot IS NULL)
    OR (asset_id IS NOT NULL AND asset_version_snapshot >= 0)),
  CONSTRAINT ck_finding_number CHECK (
    length(btrim(display_canonical_number)) BETWEEN 1 AND 128
    AND length(btrim(identity_match_key)) BETWEEN 1 AND 128),
  CONSTRAINT ck_finding_passport_observation CHECK (
    (passport_observation_state = 'ABSENT' AND passport_observation IS NULL)
    OR (passport_observation_state = 'EXPLICIT_EMPTY' AND passport_observation = '{}'::jsonb)
    OR (passport_observation_state = 'PRESENT'
      AND jsonb_typeof(passport_observation) = 'object' AND passport_observation <> '{}'::jsonb)),
  CONSTRAINT ck_finding_equipment_observation CHECK (
    (equipment_observation_state = 'ABSENT' AND equipment_observation IS NULL)
    OR (equipment_observation_state = 'EXPLICIT_EMPTY' AND equipment_observation = '[]'::jsonb)
    OR (equipment_observation_state = 'PRESENT'
      AND jsonb_typeof(equipment_observation) = 'array'
      AND jsonb_array_length(equipment_observation) > 0)),
  CONSTRAINT ck_finding_observation_states CHECK (
    passport_observation_state IN ('ABSENT','EXPLICIT_EMPTY','PRESENT')
    AND equipment_observation_state IN ('ABSENT','EXPLICIT_EMPTY','PRESENT')),
  CONSTRAINT ck_finding_mutation_state CHECK (mutation_state IN (
    'IDLE','SOURCE_CREATE_PENDING','SOURCE_CREATED','PLAN_RESOLVE_PENDING')),
  CONSTRAINT ck_finding_plan CHECK (
    (inspection = 'WORK_STAGED' AND maintenance_plan_fingerprint_sha256 ~ '^[0-9a-f]{64}$')
    OR (inspection <> 'WORK_STAGED' AND maintenance_plan_fingerprint_sha256 IS NULL)),
  CONSTRAINT ck_finding_actor CHECK (
    jsonb_typeof(actor_ref) = 'object'
    AND jsonb_exists_all(actor_ref, array['subjectId','principalType','profileRevision'])
    AND actor_ref - array['subjectId','principalType','profileRevision'] = '{}'::jsonb),
  CONSTRAINT ck_finding_times CHECK (updated_at >= created_at)
);
CREATE UNIQUE INDEX uk_finding_asset
  ON public.inventory_finding(inventory_id, asset_id) WHERE asset_id IS NOT NULL;
CREATE UNIQUE INDEX uk_finding_match_key
  ON public.inventory_finding(inventory_id, identity_match_key);
CREATE INDEX idx_finding_session_created
  ON public.inventory_finding(inventory_id, created_at, id);

ALTER TABLE public.inventory_expected_item
  ADD CONSTRAINT fk_expected_item_finding FOREIGN KEY (inventory_id, finding_id)
  REFERENCES public.inventory_finding(inventory_id, id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.finding_plan_snapshot (
  finding_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  inventory_id uuid NOT NULL,
  plan_mode varchar(16) NOT NULL,
  catalog_version_id uuid NOT NULL,
  plan_fingerprint_sha256 varchar(64) NOT NULL,
  source_snapshot jsonb NOT NULL,
  frozen_at timestamptz NOT NULL,
  CONSTRAINT finding_plan_snapshot_pkey PRIMARY KEY (finding_id, finding_revision),
  CONSTRAINT fk_finding_plan_identity FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_finding(inventory_id, id),
  CONSTRAINT ck_finding_plan_revision CHECK (finding_revision >= 0),
  CONSTRAINT ck_finding_plan_mode CHECK (plan_mode IN ('AUTO','MANUAL')),
  CONSTRAINT ck_finding_plan_hash CHECK (plan_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_finding_plan_snapshot CHECK (jsonb_typeof(source_snapshot) = 'object')
);

CREATE TABLE public.finding_plan_line (
  row_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  line_no integer NOT NULL,
  source_kind varchar(16) NOT NULL,
  line_type varchar(16) NOT NULL,
  catalog_version_id uuid,
  catalog_node_id uuid,
  description varchar(1000) NOT NULL,
  normalized_description varchar(1000),
  unit varchar(32) NOT NULL,
  quantity numeric(20,6) NOT NULL,
  unit_price_minor bigint NOT NULL,
  normative_minutes numeric(22,3) NOT NULL,
  CONSTRAINT finding_plan_line_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_finding_plan_line_no UNIQUE (finding_id, finding_revision, line_no),
  CONSTRAINT fk_finding_plan_line_snapshot FOREIGN KEY (finding_id, finding_revision)
    REFERENCES public.finding_plan_snapshot(finding_id, finding_revision),
  CONSTRAINT ck_finding_plan_line_no CHECK (line_no >= 0),
  CONSTRAINT ck_finding_line_source CHECK (source_kind IN ('CATALOG','MANUAL')),
  CONSTRAINT ck_finding_line_type CHECK (line_type IN ('WORK','MATERIAL')),
  CONSTRAINT ck_finding_line_source_fields CHECK (
    (source_kind = 'CATALOG' AND catalog_version_id IS NOT NULL
      AND catalog_node_id IS NOT NULL AND normalized_description IS NULL)
    OR (source_kind = 'MANUAL' AND catalog_version_id IS NULL
      AND catalog_node_id IS NULL AND length(btrim(normalized_description)) BETWEEN 1 AND 1000)),
  CONSTRAINT ck_finding_line_description CHECK (length(btrim(description)) BETWEEN 1 AND 1000),
  CONSTRAINT ck_finding_line_unit CHECK (length(btrim(unit)) BETWEEN 1 AND 32),
  CONSTRAINT ck_finding_line_quantity CHECK (
    quantity > 0 AND quantity <= 99999999999999.999
    AND quantity = trunc(quantity, 3)),
  CONSTRAINT ck_finding_line_price CHECK (unit_price_minor >= 0),
  CONSTRAINT ck_finding_line_normative CHECK (
    normative_minutes >= 0 AND normative_minutes <= 9223372036854775.807)
);

CREATE TABLE public.finding_plan_stage (
  row_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  stage_no integer NOT NULL,
  stage_kind varchar(32) NOT NULL,
  routing_queue_id uuid NOT NULL,
  routing_queue_code varchar(64) NOT NULL,
  routing_queue_kind varchar(64) NOT NULL,
  movement_required boolean NOT NULL,
  photo_required boolean NOT NULL,
  safe_snapshot jsonb NOT NULL,
  CONSTRAINT finding_plan_stage_pkey PRIMARY KEY (row_id),
  CONSTRAINT uk_finding_plan_stage_no UNIQUE (finding_id, finding_revision, stage_no),
  CONSTRAINT fk_finding_plan_stage_snapshot FOREIGN KEY (finding_id, finding_revision)
    REFERENCES public.finding_plan_snapshot(finding_id, finding_revision),
  CONSTRAINT ck_finding_plan_stage_no CHECK (stage_no >= 0),
  CONSTRAINT ck_finding_plan_stage_kind CHECK (
    stage_kind IN ('REPAIR_WORK','MOVE_TO_REPAIR','MOVE_FROM_REPAIR')),
  CONSTRAINT ck_finding_plan_stage_routing CHECK (
    length(btrim(routing_queue_code)) BETWEEN 1 AND 64
    AND length(btrim(routing_queue_kind)) BETWEEN 1 AND 64),
  CONSTRAINT ck_finding_plan_stage_snapshot CHECK (jsonb_typeof(safe_snapshot) = 'object')
);

CREATE TABLE public.finding_media_reference (
  finding_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  media_id uuid NOT NULL,
  generation bigint NOT NULL,
  media_kind varchar(16) NOT NULL,
  media_status varchar(16) NOT NULL,
  attached_at timestamptz NOT NULL,
  CONSTRAINT finding_media_reference_pkey PRIMARY KEY (finding_id, finding_revision, media_id, generation),
  CONSTRAINT fk_finding_media_finding FOREIGN KEY (finding_id)
    REFERENCES public.inventory_finding(id),
  CONSTRAINT ck_finding_media_revision CHECK (finding_revision >= 0),
  CONSTRAINT ck_finding_media_generation CHECK (generation >= 0),
  CONSTRAINT ck_finding_media_kind CHECK (media_kind IN ('IMAGE','VIDEO')),
  CONSTRAINT ck_finding_media_ready CHECK (media_status = 'READY')
);

CREATE TABLE public.inventory_source_attachment (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  source_key varchar(73) NOT NULL,
  source_revision bigint NOT NULL,
  technical_attempt_id uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  state varchar(24) NOT NULL,
  created_asset_id uuid,
  created_asset_version bigint,
  response_sha256 varchar(64),
  last_failure_code varchar(64),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_source_attachment_pkey PRIMARY KEY (id),
  CONSTRAINT uk_inventory_source_finding UNIQUE (inventory_id, finding_id),
  CONSTRAINT uk_inventory_source_key UNIQUE (source_key),
  CONSTRAINT uk_inventory_source_attempt UNIQUE (technical_attempt_id),
  CONSTRAINT fk_inventory_source_finding FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_finding(inventory_id, id),
  CONSTRAINT ck_inventory_source_key CHECK (
    source_key = inventory_id::text || ':' || finding_id::text),
  CONSTRAINT ck_inventory_source_revision CHECK (source_revision >= 1),
  CONSTRAINT ck_inventory_source_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_source_state CHECK (
    state IN ('PENDING','CREATED','ATTACHED','CONFLICT')),
  CONSTRAINT ck_inventory_source_result CHECK (
    (state = 'PENDING' AND created_asset_id IS NULL AND created_asset_version IS NULL
      AND response_sha256 IS NULL AND last_failure_code IS NULL)
    OR (state IN ('CREATED','ATTACHED') AND created_asset_id IS NOT NULL
      AND created_asset_version >= 0 AND response_sha256 ~ '^[0-9a-f]{64}$'
      AND last_failure_code IS NULL)
    OR (state = 'CONFLICT' AND last_failure_code IS NOT NULL)),
  CONSTRAINT ck_inventory_source_times CHECK (updated_at >= created_at)
);

CREATE TABLE public.inventory_validation_snapshot (
  inventory_id uuid NOT NULL,
  session_revision bigint NOT NULL,
  validation_sha256 varchar(64) NOT NULL,
  acknowledgement_sha256 varchar(64) NOT NULL,
  validated_at timestamptz NOT NULL,
  snapshot_body jsonb NOT NULL,
  CONSTRAINT inventory_validation_snapshot_pkey PRIMARY KEY (inventory_id),
  CONSTRAINT fk_inventory_validation_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT ck_inventory_validation_revision CHECK (session_revision >= 0),
  CONSTRAINT ck_inventory_validation_hashes CHECK (
    validation_sha256 ~ '^[0-9a-f]{64}$'
    AND acknowledgement_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_validation_body CHECK (jsonb_typeof(snapshot_body) = 'object')
);

CREATE TABLE public.inventory_validation_item (
  inventory_id uuid NOT NULL,
  asset_id uuid NOT NULL,
  found boolean NOT NULL,
  asset_version bigint,
  asset_status varchar(48),
  warehouse_id uuid,
  finding_id uuid NOT NULL,
  CONSTRAINT inventory_validation_item_pkey PRIMARY KEY (inventory_id, asset_id),
  CONSTRAINT uk_inventory_validation_finding UNIQUE (inventory_id, finding_id),
  CONSTRAINT fk_inventory_validation_snapshot FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_validation_snapshot(inventory_id),
  CONSTRAINT fk_inventory_validation_finding FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_finding(inventory_id, id),
  CONSTRAINT fk_inventory_validation_warehouse FOREIGN KEY (warehouse_id, inventory_id)
    REFERENCES public.inventory_session(warehouse_id, id),
  CONSTRAINT ck_inventory_validation_result CHECK (
    (found AND asset_version >= 0 AND length(btrim(asset_status)) BETWEEN 1 AND 48
      AND warehouse_id IS NOT NULL)
    OR (NOT found AND asset_version IS NULL AND asset_status IS NULL AND warehouse_id IS NULL))
);

CREATE TABLE public.inventory_completion_statistics (
  inventory_id uuid NOT NULL,
  expected_count integer NOT NULL,
  inspected_count integer NOT NULL,
  missing_count integer NOT NULL,
  ready_count integer NOT NULL,
  with_work_count integer NOT NULL,
  added_count integer NOT NULL,
  unexpected_existing_count integer NOT NULL,
  conflict_count integer NOT NULL,
  work_line_count integer NOT NULL,
  material_line_count integer NOT NULL,
  work_total_minor bigint NOT NULL,
  material_total_minor bigint NOT NULL,
  grand_total_minor bigint NOT NULL,
  rounding_adjustment_minor smallint NOT NULL,
  normative_minutes numeric(22,3) NOT NULL,
  duration_seconds bigint NOT NULL,
  frozen_at timestamptz NOT NULL,
  CONSTRAINT inventory_completion_statistics_pkey PRIMARY KEY (inventory_id),
  CONSTRAINT fk_inventory_statistics_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT ck_inventory_statistics_counts CHECK (
    expected_count >= 0 AND inspected_count >= 0 AND missing_count >= 0
    AND ready_count >= 0 AND with_work_count >= 0 AND added_count >= 0
    AND unexpected_existing_count >= 0 AND conflict_count >= 0
    AND work_line_count >= 0 AND material_line_count >= 0),
  CONSTRAINT ck_inventory_statistics_totals CHECK (
    work_total_minor >= 0 AND material_total_minor >= 0 AND grand_total_minor >= 0
    AND rounding_adjustment_minor IN (-1,0,1)
    AND grand_total_minor = work_total_minor + material_total_minor + rounding_adjustment_minor),
  CONSTRAINT ck_inventory_statistics_time CHECK (
    normative_minutes >= 0 AND normative_minutes <= 9223372036854775.807
    AND duration_seconds >= 0)
);

CREATE TABLE public.inventory_statistics_line (
  row_id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  aggregation_kind varchar(16) NOT NULL,
  catalog_version_id uuid,
  catalog_node_id uuid,
  normalized_description varchar(1000),
  line_type varchar(16) NOT NULL,
  unit varchar(32) NOT NULL,
  unit_price_minor bigint NOT NULL,
  quantity numeric(20,6) NOT NULL,
  row_total_minor bigint NOT NULL,
  CONSTRAINT inventory_statistics_line_pkey PRIMARY KEY (row_id),
  CONSTRAINT fk_inventory_statistics_line FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_completion_statistics(inventory_id),
  CONSTRAINT ck_inventory_statistics_line_kind CHECK (aggregation_kind IN ('CATALOG','MANUAL')),
  CONSTRAINT ck_inventory_statistics_line_key CHECK (
    (aggregation_kind = 'CATALOG' AND catalog_version_id IS NOT NULL
      AND catalog_node_id IS NOT NULL AND normalized_description IS NULL)
    OR (aggregation_kind = 'MANUAL' AND catalog_version_id IS NULL
      AND catalog_node_id IS NULL AND length(btrim(normalized_description)) BETWEEN 1 AND 1000)),
  CONSTRAINT ck_inventory_statistics_line_type CHECK (line_type IN ('WORK','MATERIAL')),
  CONSTRAINT ck_inventory_statistics_line_values CHECK (
    length(btrim(unit)) BETWEEN 1 AND 32 AND unit_price_minor >= 0
    AND quantity > 0 AND quantity <= 99999999999999.999
    AND quantity = trunc(quantity, 3) AND row_total_minor >= 0)
);
CREATE UNIQUE INDEX uk_inventory_statistics_catalog_line
  ON public.inventory_statistics_line(
    inventory_id, catalog_version_id, catalog_node_id, line_type, unit, unit_price_minor)
  WHERE aggregation_kind = 'CATALOG';
CREATE UNIQUE INDEX uk_inventory_statistics_manual_line
  ON public.inventory_statistics_line(
    inventory_id, normalized_description, line_type, unit, unit_price_minor)
  WHERE aggregation_kind = 'MANUAL';

CREATE TABLE public.inventory_publication_intent (
  id uuid NOT NULL,
  inventory_id uuid NOT NULL,
  finding_id uuid NOT NULL,
  publication_revision bigint NOT NULL DEFAULT 0,
  state varchar(24) NOT NULL,
  maintenance_source_key varchar(73) NOT NULL,
  source_revision bigint NOT NULL,
  request_sha256 varchar(64),
  current_precondition_sha256 varchar(64),
  maintenance_repair_id uuid,
  attempt_count integer NOT NULL DEFAULT 0,
  blocked_failure_code varchar(64),
  closed_reason varchar(2000),
  closed_actor_ref jsonb,
  closed_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_intent_pkey PRIMARY KEY (id),
  CONSTRAINT uk_inventory_publication_finding UNIQUE (inventory_id, finding_id),
  CONSTRAINT uk_inventory_publication_source UNIQUE (maintenance_source_key),
  CONSTRAINT fk_inventory_publication_finding FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_finding(inventory_id, id),
  CONSTRAINT ck_inventory_publication_revision CHECK (publication_revision >= 0),
  CONSTRAINT ck_inventory_publication_state CHECK (state IN (
    'NOT_REQUIRED','READY','PENDING','SUCCEEDED','TRANSIENT_FAILED','BLOCKED','CLOSED_BLOCKED')),
  CONSTRAINT ck_inventory_publication_source CHECK (
    maintenance_source_key = inventory_id::text || ':' || finding_id::text
    AND source_revision >= 1),
  CONSTRAINT ck_inventory_publication_hashes CHECK (
    (request_sha256 IS NULL OR request_sha256 ~ '^[0-9a-f]{64}$')
    AND (current_precondition_sha256 IS NULL OR current_precondition_sha256 ~ '^[0-9a-f]{64}$')),
  CONSTRAINT ck_inventory_publication_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_publication_success CHECK (
    (state = 'SUCCEEDED' AND maintenance_repair_id IS NOT NULL)
    OR (state <> 'SUCCEEDED' AND maintenance_repair_id IS NULL)),
  CONSTRAINT ck_inventory_publication_block CHECK (
    (state IN ('BLOCKED','CLOSED_BLOCKED') AND blocked_failure_code IS NOT NULL)
    OR (state NOT IN ('BLOCKED','CLOSED_BLOCKED') AND blocked_failure_code IS NULL)),
  CONSTRAINT ck_inventory_publication_closed CHECK (
    (state = 'CLOSED_BLOCKED' AND length(btrim(closed_reason)) BETWEEN 1 AND 2000
      AND closed_actor_ref IS NOT NULL AND jsonb_typeof(closed_actor_ref) = 'object'
      AND closed_at IS NOT NULL)
    OR (state <> 'CLOSED_BLOCKED' AND closed_reason IS NULL
      AND closed_actor_ref IS NULL AND closed_at IS NULL)),
  CONSTRAINT ck_inventory_publication_times CHECK (updated_at >= created_at)
);
CREATE INDEX idx_inventory_publication_dispatch
  ON public.inventory_publication_intent(state, updated_at, id)
  WHERE state IN ('PENDING','TRANSIENT_FAILED');

CREATE TABLE public.inventory_publication_attempt (
  id uuid NOT NULL,
  publication_intent_id uuid NOT NULL,
  attempt_no integer NOT NULL,
  idempotency_key uuid NOT NULL,
  transition_kind varchar(24) NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  started_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_attempt_pkey PRIMARY KEY (id),
  CONSTRAINT uk_publication_attempt_no UNIQUE (publication_intent_id, attempt_no),
  CONSTRAINT uk_publication_attempt_key UNIQUE (publication_intent_id, idempotency_key),
  CONSTRAINT fk_publication_attempt_intent FOREIGN KEY (publication_intent_id)
    REFERENCES public.inventory_publication_intent(id),
  CONSTRAINT ck_publication_attempt_no CHECK (attempt_no >= 1),
  CONSTRAINT ck_publication_attempt_kind CHECK (
    transition_kind IN ('REQUEST','RETRY','RECONCILE_RETRY')),
  CONSTRAINT ck_publication_attempt_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.inventory_publication_attempt_result (
  publication_attempt_id uuid NOT NULL,
  outcome varchar(24) NOT NULL,
  failure_code varchar(64),
  message_sha256 varchar(64),
  repair_id uuid,
  finished_at timestamptz NOT NULL,
  CONSTRAINT inventory_publication_attempt_result_pkey PRIMARY KEY (publication_attempt_id),
  CONSTRAINT fk_publication_attempt_result_attempt FOREIGN KEY (publication_attempt_id)
    REFERENCES public.inventory_publication_attempt(id),
  CONSTRAINT ck_publication_attempt_result_outcome CHECK (
    outcome IN ('SUCCEEDED','TRANSIENT_FAILED','BLOCKED')),
  CONSTRAINT ck_publication_attempt_result_shape CHECK (
    (outcome = 'SUCCEEDED' AND failure_code IS NULL
      AND message_sha256 IS NULL AND repair_id IS NOT NULL)
    OR (outcome IN ('TRANSIENT_FAILED','BLOCKED')
      AND length(btrim(failure_code)) BETWEEN 1 AND 64
      AND message_sha256 ~ '^[0-9a-f]{64}$' AND repair_id IS NULL))
);

CREATE TABLE public.inventory_media_fact_projection (
  media_id uuid NOT NULL,
  generation bigint NOT NULL,
  media_aggregate_version bigint NOT NULL,
  owner_type varchar(32) NOT NULL,
  owner_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  media_kind varchar(16) NOT NULL,
  media_status varchar(16) NOT NULL,
  rotation_degrees smallint NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_media_fact_projection_pkey PRIMARY KEY (media_id, generation),
  CONSTRAINT ck_inventory_media_version CHECK (
    generation >= 0 AND media_aggregate_version >= 0),
  CONSTRAINT ck_inventory_media_owner CHECK (owner_type = 'INVENTORY_FINDING'),
  CONSTRAINT ck_inventory_media_kind CHECK (media_kind IN ('IMAGE','VIDEO')),
  CONSTRAINT ck_inventory_media_status CHECK (
    media_status IN ('UPLOADING','PROCESSING','READY','FAILED','DELETED')),
  CONSTRAINT ck_inventory_media_rotation CHECK (rotation_degrees IN (0,90,180,270))
);
CREATE INDEX idx_inventory_media_owner
  ON public.inventory_media_fact_projection(owner_id, warehouse_id, media_status);

CREATE TABLE public.inventory_idempotency_record (
  subject_id uuid NOT NULL,
  command_scope varchar(96) NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 varchar(64) NOT NULL,
  state varchar(16) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 1,
  lease_token uuid,
  lease_until timestamptz,
  response_status integer,
  response_body jsonb,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT inventory_idempotency_record_pkey PRIMARY KEY (subject_id, command_scope, idempotency_key),
  CONSTRAINT ck_inventory_idempotency_scope CHECK (length(btrim(command_scope)) BETWEEN 1 AND 96),
  CONSTRAINT ck_inventory_idempotency_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_idempotency_state CHECK (state IN ('IN_PROGRESS','COMPLETED')),
  CONSTRAINT ck_inventory_idempotency_attempts CHECK (attempt_count >= 1),
  CONSTRAINT ck_inventory_idempotency_result CHECK (
    (state = 'IN_PROGRESS' AND lease_token IS NOT NULL AND lease_until IS NOT NULL
      AND response_status IS NULL AND response_body IS NULL)
    OR (state = 'COMPLETED' AND lease_token IS NULL AND lease_until IS NULL
      AND response_status BETWEEN 200 AND 299
      AND jsonb_typeof(response_body) IN ('object','array'))),
  CONSTRAINT ck_inventory_idempotency_retention CHECK (
    updated_at >= created_at AND expires_at = created_at + interval '7 days')
);
CREATE INDEX idx_inventory_idempotency_expiry
  ON public.inventory_idempotency_record(expires_at);
CREATE INDEX idx_inventory_idempotency_reclaim
  ON public.inventory_idempotency_record(lease_until)
  WHERE state = 'IN_PROGRESS';

CREATE TABLE public.event_stream_head (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  current_version bigint NOT NULL,
  last_event_id uuid NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT event_stream_head_pkey PRIMARY KEY (aggregate_type, aggregate_id),
  CONSTRAINT ck_inventory_stream_type CHECK (
    aggregate_type IN ('SESSION','FINDING','PUBLICATION')),
  CONSTRAINT ck_inventory_stream_version CHECK (current_version >= 0)
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
  event_body jsonb NOT NULL,
  event_sha256 char(64) NOT NULL,
  CONSTRAINT domain_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_inventory_domain_stream_version UNIQUE (
    aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT uk_inventory_domain_event_identity UNIQUE (
    event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_inventory_domain_type CHECK (
    aggregate_type IN ('SESSION','FINDING','PUBLICATION')),
  CONSTRAINT ck_inventory_domain_version CHECK (aggregate_version >= 0 AND event_version = 1),
  CONSTRAINT ck_inventory_domain_event_type CHECK (event_type IN (
    'inventory.session.started.v1','inventory.finding.added.v1',
    'inventory.finding.inspection-saved.v1','inventory.finding.owner-proof.v1',
    'inventory.session.completed.v1','inventory.session.cancelled.v1',
    'inventory.publication.ready.v1','inventory.publication.requested.v1',
    'inventory.publication.succeeded.v1','inventory.publication.transient-failed.v1',
    'inventory.publication.blocked.v1','inventory.publication.closed-blocked.v1')),
  CONSTRAINT ck_inventory_domain_event_family CHECK (
    (aggregate_type = 'SESSION' AND event_type IN (
      'inventory.session.started.v1','inventory.session.completed.v1','inventory.session.cancelled.v1'))
    OR (aggregate_type = 'FINDING' AND event_type IN (
      'inventory.finding.added.v1','inventory.finding.inspection-saved.v1',
      'inventory.finding.owner-proof.v1'))
    OR (aggregate_type = 'PUBLICATION' AND event_type LIKE 'inventory.publication.%')),
  CONSTRAINT ck_inventory_domain_body CHECK (
    jsonb_typeof(event_body) = 'object' AND event_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_domain_actor CHECK (
    actor_ref IS NULL OR jsonb_typeof(actor_ref) = 'object')
);

CREATE TABLE public.aggregate_snapshot (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  snapshot_body jsonb NOT NULL,
  snapshot_sha256 char(64) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT aggregate_snapshot_pkey PRIMARY KEY (
    aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT ck_inventory_snapshot_type CHECK (
    aggregate_type IN ('SESSION','FINDING','PUBLICATION')),
  CONSTRAINT ck_inventory_snapshot_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_inventory_snapshot_body CHECK (
    jsonb_typeof(snapshot_body) = 'object' AND snapshot_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.projection_checkpoint (
  projection_name varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  projection_sha256 char(64) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (
    projection_name, aggregate_type, aggregate_id),
  CONSTRAINT ck_inventory_projection_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_inventory_projection_hash CHECK (projection_sha256 ~ '^[0-9a-f]{64}$')
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
  created_at timestamptz NOT NULL,
  CONSTRAINT outbox_event_pkey PRIMARY KEY (event_id),
  CONSTRAINT uk_inventory_outbox_stream_version UNIQUE (
    aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_inventory_outbox_event FOREIGN KEY (
    event_id, aggregate_type, aggregate_id, aggregate_version, event_type)
    REFERENCES public.domain_event(
      event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_inventory_outbox_topic CHECK (
    topic IN ('rwms.inventory.session.v1','rwms.inventory.publication.v1')),
  CONSTRAINT ck_inventory_outbox_status CHECK (
    status IN ('PENDING','IN_FLIGHT','PUBLISHED','DLT','QUARANTINED')),
  CONSTRAINT ck_inventory_outbox_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_outbox_body CHECK (
    jsonb_typeof(envelope_body) = 'object' AND envelope_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_outbox_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL
      AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL
      AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_inventory_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT ck_inventory_outbox_dlt CHECK ((status = 'DLT') = (dlt_at IS NOT NULL))
);
CREATE INDEX idx_inventory_outbox_pending
  ON public.outbox_event(next_attempt_at, created_at, event_id) WHERE status = 'PENDING';
CREATE INDEX idx_inventory_outbox_reclaim
  ON public.outbox_event(lease_until, created_at, event_id) WHERE status = 'IN_FLIGHT';

CREATE TABLE public.inbox_message (
  consumer_group varchar(128) NOT NULL,
  event_id uuid NOT NULL,
  source_topic varchar(192) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  record_key varchar(128) NOT NULL,
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
  CONSTRAINT ck_inventory_inbox_topic CHECK (source_topic = 'rwms.media.media.v1'),
  CONSTRAINT ck_inventory_inbox_key CHECK (record_key = aggregate_id),
  CONSTRAINT ck_inventory_inbox_status CHECK (
    status IN ('RECEIVED','PROCESSED','RETRY','DLT','QUARANTINED')),
  CONSTRAINT ck_inventory_inbox_version CHECK (
    aggregate_version >= 0 AND attempt_count >= 0),
  CONSTRAINT ck_inventory_inbox_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_inbox_body CHECK (jsonb_typeof(envelope_body) = 'object'),
  CONSTRAINT ck_inventory_inbox_result CHECK (
    (status = 'PROCESSED' AND processed_at IS NOT NULL)
    OR (status = 'RETRY' AND next_attempt_at IS NOT NULL)
    OR (status = 'DLT' AND dlt_at IS NOT NULL)
    OR (status = 'QUARANTINED' AND quarantine_reason IS NOT NULL)
    OR status = 'RECEIVED')
);
CREATE INDEX idx_inventory_inbox_retry
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
  CONSTRAINT consumer_aggregate_checkpoint_pkey PRIMARY KEY (
    consumer_group, aggregate_type, aggregate_id),
  CONSTRAINT ck_inventory_consumer_checkpoint CHECK (
    (last_aggregate_version = -1 AND last_event_id IS NULL)
    OR (last_aggregate_version >= 0 AND last_event_id IS NOT NULL)),
  CONSTRAINT ck_inventory_consumer_blocked CHECK (
    (blocked AND quarantine_reason IS NOT NULL)
    OR (NOT blocked AND quarantine_reason IS NULL))
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
  CONSTRAINT uk_inventory_version_gap_event UNIQUE (consumer_group, received_event_id),
  CONSTRAINT ck_inventory_version_gap_versions CHECK (
    expected_version >= 0 AND received_version > expected_version),
  CONSTRAINT ck_inventory_version_gap_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_version_gap_reason CHECK (reason_code = 'AGGREGATE_VERSION_GAP'),
  CONSTRAINT ck_inventory_version_gap_status CHECK (
    (status = 'OPEN' AND resolved_at IS NULL
      AND resolution_reason IS NULL AND resolved_by_subject_id IS NULL)
    OR (status = 'RESOLVED' AND resolved_at IS NOT NULL
      AND length(btrim(resolution_reason)) BETWEEN 1 AND 500
      AND resolved_by_subject_id IS NOT NULL))
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
  CONSTRAINT sanitized_dead_letter_pkey PRIMARY KEY (dlt_id),
  CONSTRAINT ck_inventory_dlt_destination CHECK (destination = 'rwms.inventory.dlt.v1'),
  CONSTRAINT ck_inventory_dlt_failure CHECK (failure_code IN (
    'VALIDATION_REJECTED','PROCESSING_FAILED','EVENT_ID_CONFLICT')),
  CONSTRAINT ck_inventory_dlt_status CHECK (
    status IN ('PENDING','IN_FLIGHT','PUBLISHED','FAILED')),
  CONSTRAINT ck_inventory_dlt_hashes CHECK (
    message_sha256 ~ '^[0-9a-f]{64}$' AND body_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_dlt_body CHECK (
    jsonb_typeof(safe_body) = 'object'
    AND jsonb_exists_all(safe_body, array['failureCode','messageSha256','recordedAt'])
    AND safe_body - array['failureCode','messageSha256','recordedAt'] = '{}'::jsonb
    AND safe_body->>'failureCode' = failure_code
    AND safe_body->>'messageSha256' = message_sha256),
  CONSTRAINT ck_inventory_dlt_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_inventory_dlt_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL
      AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL
      AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_inventory_dlt_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX idx_inventory_dlt_pending
  ON public.sanitized_dead_letter(next_attempt_at, created_at, dlt_id)
  WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION public.enforce_finding_plan_line_limit()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM public.inventory_finding WHERE id = NEW.finding_id FOR UPDATE;
  IF (SELECT count(*) FROM public.finding_plan_line
      WHERE finding_id = NEW.finding_id AND finding_revision = NEW.finding_revision) > 2000 THEN
    RAISE EXCEPTION 'finding plan line limit exceeded' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.enforce_finding_plan_stage_limit()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM public.inventory_finding WHERE id = NEW.finding_id FOR UPDATE;
  IF (SELECT count(*) FROM public.finding_plan_stage
      WHERE finding_id = NEW.finding_id AND finding_revision = NEW.finding_revision) > 1000 THEN
    RAISE EXCEPTION 'finding plan stage limit exceeded' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.enforce_finding_media_limit()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM public.inventory_finding WHERE id = NEW.finding_id FOR UPDATE;
  IF (SELECT count(*) FROM public.finding_media_reference
      WHERE finding_id = NEW.finding_id AND finding_revision = NEW.finding_revision) > 100 THEN
    RAISE EXCEPTION 'finding media reference limit exceeded' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ck_finding_plan_line_limit
AFTER INSERT OR UPDATE ON public.finding_plan_line
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION public.enforce_finding_plan_line_limit();

CREATE CONSTRAINT TRIGGER ck_finding_plan_stage_limit
AFTER INSERT OR UPDATE ON public.finding_plan_stage
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION public.enforce_finding_plan_stage_limit();

CREATE CONSTRAINT TRIGGER ck_finding_media_limit
AFTER INSERT OR UPDATE ON public.finding_media_reference
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION public.enforce_finding_media_limit();

CREATE OR REPLACE FUNCTION public.reject_inventory_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION public.validate_publication_attempt_result_time()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.finished_at < (
      SELECT attempt.started_at FROM public.inventory_publication_attempt attempt
      WHERE attempt.id = NEW.publication_attempt_id) THEN
    RAISE EXCEPTION 'publication attempt result predates its request' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.validate_start_capture_attempt_sequence()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  latest_attempt bigint;
BEGIN
  PERFORM 1 FROM public.inventory_start_operation
    WHERE operation_id = NEW.operation_id FOR UPDATE;
  SELECT max(attempt.technical_attempt) INTO latest_attempt
    FROM public.inventory_start_capture_attempt attempt
    WHERE attempt.operation_id = NEW.operation_id;
  IF NEW.technical_attempt <> coalesce(latest_attempt, 0) + 1 THEN
    RAISE EXCEPTION 'start capture technical attempt is not contiguous'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER domain_event_append_only
BEFORE UPDATE OR DELETE ON public.domain_event
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

CREATE TRIGGER aggregate_snapshot_append_only
BEFORE UPDATE OR DELETE ON public.aggregate_snapshot
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

CREATE TRIGGER publication_attempt_append_only
BEFORE UPDATE OR DELETE ON public.inventory_publication_attempt
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

CREATE TRIGGER publication_attempt_result_append_only
BEFORE UPDATE OR DELETE ON public.inventory_publication_attempt_result
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

CREATE TRIGGER publication_attempt_result_time
BEFORE INSERT ON public.inventory_publication_attempt_result
FOR EACH ROW EXECUTE FUNCTION public.validate_publication_attempt_result_time();

CREATE TRIGGER start_capture_attempt_append_only
BEFORE UPDATE OR DELETE ON public.inventory_start_capture_attempt
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();

CREATE TRIGGER start_capture_attempt_sequence
BEFORE INSERT ON public.inventory_start_capture_attempt
FOR EACH ROW EXECUTE FUNCTION public.validate_start_capture_attempt_sequence();

CREATE TRIGGER start_capture_result_append_only
BEFORE UPDATE OR DELETE ON public.inventory_start_capture_result
FOR EACH ROW EXECUTE FUNCTION public.reject_inventory_append_only_mutation();
