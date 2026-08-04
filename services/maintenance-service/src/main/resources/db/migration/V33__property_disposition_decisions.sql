-- One maintenance-owned decision is the durable root for a write-off or loss of exactly one
-- cabin or equipment asset. Approval and the eventual asset effect intentionally remain separate.

CREATE TABLE public.property_disposition_decision (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  recovery_version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  asset_kind varchar(16) NOT NULL,
  asset_id uuid NOT NULL,
  asset_display_name varchar(255) NOT NULL,
  disposition_kind varchar(16) NOT NULL,
  source varchar(16) NOT NULL,
  state varchar(24) NOT NULL,
  asset_effect_state varchar(16) NOT NULL,
  contents_mode varchar(32),
  expected_asset_version bigint NOT NULL,
  expected_source_balance_version bigint,
  quantity bigint,
  reason varchar(2000) NOT NULL,
  evidence_link varchar(2048),
  source_repair_id uuid,
  root_repair_id uuid,
  inventory_id uuid,
  finding_id uuid,
  initiated_by_subject_id uuid,
  idempotency_key uuid,
  request_sha256 char(64) NOT NULL,
  initiated_by_actor_snapshot jsonb NOT NULL,
  reviewed_by_actor_snapshot jsonb,
  review_comment varchar(2000),
  rejection_reason varchar(2000),
  movement_task_id uuid,
  effect_id uuid,
  failure_code varchar(128),
  failure_detail varchar(2000),
  quarantine_resume_state varchar(24),
  recovery_reason varchar(2000),
  recovery_actor_snapshot jsonb,
  reviewed_at timestamptz,
  quarantined_at timestamptz,
  recovered_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT property_disposition_decision_pkey PRIMARY KEY (id),
  CONSTRAINT uq_property_disposition_decision_subject_idempotency
    UNIQUE (initiated_by_subject_id, idempotency_key),
  CONSTRAINT ck_property_disposition_decision_versions CHECK (
    version >= 0
    AND recovery_version >= 0
    AND expected_asset_version >= 0
    AND (expected_source_balance_version IS NULL OR expected_source_balance_version >= 0)),
  CONSTRAINT ck_property_disposition_decision_asset_kind CHECK (
    (asset_kind = 'EQUIPMENT'
      AND quantity IS NOT NULL
      AND quantity > 0
      AND expected_source_balance_version IS NOT NULL
      AND contents_mode IS NULL)
    OR
    (asset_kind = 'CABIN'
      AND quantity IS NULL
      AND expected_source_balance_version IS NULL)),
  CONSTRAINT ck_property_disposition_decision_kind CHECK (
    disposition_kind IN ('WRITE_OFF', 'LOSS')),
  CONSTRAINT ck_property_disposition_decision_source CHECK (
    source IN ('MANUAL', 'REPAIR', 'ESTIMATE', 'INVENTORY')),
  CONSTRAINT ck_property_disposition_decision_state CHECK (
    state IN (
      'PENDING_APPROVAL', 'APPROVED', 'MOVEMENT_PENDING', 'EFFECT_PENDING',
      'EFFECTIVE', 'REJECTED', 'QUARANTINED')),
  CONSTRAINT ck_property_disposition_decision_effect_state CHECK (
    (state IN ('PENDING_APPROVAL', 'APPROVED', 'MOVEMENT_PENDING', 'REJECTED')
      AND asset_effect_state = 'NOT_STARTED')
    OR (state = 'EFFECT_PENDING' AND asset_effect_state = 'PENDING')
    OR (state = 'EFFECTIVE' AND asset_effect_state = 'APPLIED')
    OR (state = 'QUARANTINED' AND asset_effect_state = 'QUARANTINED')),
  CONSTRAINT ck_property_disposition_decision_contents_mode CHECK (
    contents_mode IS NULL
    OR (asset_kind = 'CABIN'
      AND contents_mode IN ('MOVE_SELECTED_TO_STOCK', 'DISPOSE_WITH_CABIN'))),
  CONSTRAINT ck_property_disposition_decision_review CHECK (
    (state <> 'REJECTED'
      OR (reviewed_by_actor_snapshot IS NOT NULL
        AND rejection_reason IS NOT NULL
        AND btrim(rejection_reason) <> ''))
    AND (reviewed_by_actor_snapshot IS NULL OR jsonb_typeof(reviewed_by_actor_snapshot) = 'object')
    AND (review_comment IS NULL OR btrim(review_comment) <> '')
    AND (rejection_reason IS NULL OR btrim(rejection_reason) <> '')),
  CONSTRAINT ck_property_disposition_decision_quarantine CHECK (
    (state <> 'QUARANTINED'
      OR (failure_code IS NOT NULL
        AND btrim(failure_code) <> ''
        AND failure_detail IS NOT NULL
        AND btrim(failure_detail) <> ''
        AND quarantine_resume_state IN ('APPROVED', 'MOVEMENT_PENDING', 'EFFECT_PENDING')))
    AND (quarantine_resume_state IS NULL
      OR quarantine_resume_state IN ('APPROVED', 'MOVEMENT_PENDING', 'EFFECT_PENDING'))
    AND (recovery_reason IS NULL OR btrim(recovery_reason) <> '')
    AND (recovery_actor_snapshot IS NULL OR jsonb_typeof(recovery_actor_snapshot) = 'object')),
  CONSTRAINT ck_property_disposition_decision_effect CHECK (
    (state <> 'EFFECTIVE' OR effect_id IS NOT NULL)
    AND (effect_id IS NULL OR state = 'EFFECTIVE')
    AND (state <> 'MOVEMENT_PENDING' OR movement_task_id IS NOT NULL)),
  CONSTRAINT ck_property_disposition_decision_required_text CHECK (
    btrim(asset_display_name) <> ''
    AND btrim(reason) <> ''
    AND request_sha256 ~ '^[0-9a-f]{64}$'
    AND jsonb_typeof(initiated_by_actor_snapshot) = 'object'
    AND (evidence_link IS NULL OR btrim(evidence_link) <> '')),
  CONSTRAINT ck_property_disposition_decision_manual_idempotency CHECK (
    (initiated_by_subject_id IS NULL) = (idempotency_key IS NULL)
    AND (source <> 'MANUAL'
      OR (initiated_by_subject_id IS NOT NULL AND idempotency_key IS NOT NULL))),
  CONSTRAINT ck_property_disposition_decision_inventory_source CHECK (
    source <> 'INVENTORY' OR (inventory_id IS NOT NULL AND finding_id IS NOT NULL))
);

CREATE TABLE public.property_disposition_contents_snapshot_line (
  id uuid NOT NULL,
  decision_id uuid NOT NULL,
  equipment_id uuid NOT NULL,
  equipment_name varchar(255) NOT NULL,
  equipment_format varchar(512),
  current_quantity bigint NOT NULL,
  move_quantity bigint NOT NULL,
  expected_balance_version bigint NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT property_disposition_contents_snapshot_line_pkey PRIMARY KEY (id),
  CONSTRAINT uq_property_disposition_contents_snapshot_line_equipment
    UNIQUE (decision_id, equipment_id),
  CONSTRAINT fk_property_disposition_contents_snapshot_line_decision
    FOREIGN KEY (decision_id)
    REFERENCES public.property_disposition_decision(id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_property_disposition_contents_snapshot_line_quantities CHECK (
    current_quantity > 0
    AND move_quantity >= 0
    AND move_quantity <= current_quantity
    AND expected_balance_version >= 0),
  CONSTRAINT ck_property_disposition_contents_snapshot_line_text CHECK (
    btrim(equipment_name) <> ''
    AND (equipment_format IS NULL OR btrim(equipment_format) <> ''))
);

CREATE INDEX idx_property_disposition_decision_kind_state_warehouse
  ON public.property_disposition_decision(
    disposition_kind, state, warehouse_id, created_at DESC, id);
CREATE INDEX idx_property_disposition_decision_root_repair
  ON public.property_disposition_decision(root_repair_id, created_at DESC, id)
  WHERE root_repair_id IS NOT NULL;
CREATE INDEX idx_property_disposition_contents_snapshot_line_decision
  ON public.property_disposition_contents_snapshot_line(decision_id, equipment_id);

-- A repair chain has a single root-asset decision; repeated callbacks must read the existing row.
CREATE UNIQUE INDEX uq_property_disposition_decision_repair_root
  ON public.property_disposition_decision(root_repair_id)
  WHERE source IN ('REPAIR', 'ESTIMATE') AND root_repair_id IS NOT NULL;

-- Inventory publication is at-least-once, so its immutable finding identity is the deduplication key.
CREATE UNIQUE INDEX uq_property_disposition_decision_inventory_finding
  ON public.property_disposition_decision(inventory_id, finding_id)
  WHERE source = 'INVENTORY' AND inventory_id IS NOT NULL AND finding_id IS NOT NULL;
