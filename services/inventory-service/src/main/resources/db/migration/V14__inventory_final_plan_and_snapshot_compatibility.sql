-- V12 frozen plans remain immutable evidence. Their legacy outbound marker is retained in raw
-- JSON and fingerprints; new plans are marked schema v2 and have no outbound movement marker.
ALTER TABLE public.finding_plan_snapshot
  ADD COLUMN snapshot_schema_version smallint NOT NULL DEFAULT 1,
  ADD CONSTRAINT ck_finding_plan_snapshot_schema_version
    CHECK (snapshot_schema_version IN (1, 2));

-- Membership changes now retain the historic finding instead of mutating it back into the
-- active population, so their audit events are first-class domain events as well.
ALTER TABLE public.domain_event
  DROP CONSTRAINT ck_inventory_domain_event_type,
  DROP CONSTRAINT ck_inventory_domain_event_family,
  ADD CONSTRAINT ck_inventory_domain_event_type CHECK (event_type IN (
    'inventory.session.started.v1','inventory.finding.added.v1',
    'inventory.finding.inspection-saved.v1','inventory.finding.owner-proof.v1',
    'inventory.finding.membership-departed.v1','inventory.finding.membership-refreshed.v1',
    'inventory.session.completed.v1','inventory.session.cancelled.v1',
    'inventory.publication.ready.v1','inventory.publication.requested.v1',
    'inventory.publication.succeeded.v1','inventory.publication.transient-failed.v1',
    'inventory.publication.blocked.v1','inventory.publication.closed-blocked.v1')),
  ADD CONSTRAINT ck_inventory_domain_event_family CHECK (
    (aggregate_type = 'SESSION' AND event_type IN (
      'inventory.session.started.v1','inventory.session.completed.v1','inventory.session.cancelled.v1'))
    OR (aggregate_type = 'FINDING' AND event_type IN (
      'inventory.finding.added.v1','inventory.finding.inspection-saved.v1',
      'inventory.finding.owner-proof.v1','inventory.finding.membership-departed.v1',
      'inventory.finding.membership-refreshed.v1'))
    OR (aggregate_type = 'PUBLICATION' AND event_type LIKE 'inventory.publication.%'));

-- A cabin that departs and returns receives a new finding and mandatory new inspection. The
-- original immutable start row remains audit evidence, so its old identity uniqueness is removed.
ALTER TABLE public.inventory_expected_item
  DROP CONSTRAINT uk_expected_item_asset,
  DROP CONSTRAINT uk_expected_item_match_key;

CREATE INDEX idx_inventory_expected_item_identity_history
  ON public.inventory_expected_item(inventory_id, identity_match_key, captured_at, row_id);

-- The live population remains unique, while departed rows remain immutable audit history and a
-- later return can create a new expected finding for the same asset/identity.
DROP INDEX public.uk_finding_asset;
DROP INDEX public.uk_finding_match_key;
CREATE UNIQUE INDEX uk_finding_asset
  ON public.inventory_finding(inventory_id, asset_id)
  WHERE membership_active AND asset_id IS NOT NULL;
CREATE UNIQUE INDEX uk_finding_match_key
  ON public.inventory_finding(inventory_id, identity_match_key)
  WHERE membership_active;

CREATE TABLE public.inventory_planning_settings (
  warehouse_id uuid NOT NULL,
  settings_revision bigint NOT NULL DEFAULT 0,
  movement_daily_capacity integer NOT NULL,
  repair_daily_capacity integer NOT NULL,
  working_weekdays jsonb NOT NULL,
  holidays jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_planning_settings_pkey PRIMARY KEY (warehouse_id),
  CONSTRAINT ck_inventory_planning_settings_revision CHECK (settings_revision >= 0),
  CONSTRAINT ck_inventory_planning_settings_capacity CHECK (
    movement_daily_capacity BETWEEN 1 AND 1000
    AND repair_daily_capacity BETWEEN 1 AND 1000),
  CONSTRAINT ck_inventory_planning_settings_calendar CHECK (
    jsonb_typeof(working_weekdays) = 'array'
    AND jsonb_array_length(working_weekdays) BETWEEN 1 AND 7
    AND jsonb_typeof(holidays) = 'array'
    AND jsonb_array_length(holidays) <= 3660),
  CONSTRAINT ck_inventory_planning_settings_times CHECK (updated_at >= created_at)
);

CREATE TABLE public.inventory_final_plan (
  inventory_id uuid NOT NULL,
  row_revision bigint NOT NULL DEFAULT 0,
  final_plan_version bigint NOT NULL,
  state varchar(16) NOT NULL,
  basis_session_revision bigint NOT NULL,
  planning_settings_revision bigint NOT NULL,
  final_plan_sha256 varchar(64) NOT NULL,
  movement_schedule_mode varchar(16) NOT NULL,
  repair_schedule_mode varchar(16) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT inventory_final_plan_pkey PRIMARY KEY (inventory_id),
  CONSTRAINT fk_inventory_final_plan_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT ck_inventory_final_plan_revisions CHECK (
    row_revision >= 0 AND final_plan_version >= 1
    AND basis_session_revision >= 0 AND planning_settings_revision >= 0),
  CONSTRAINT ck_inventory_final_plan_state CHECK (state IN ('DRAFT', 'STALE', 'COMPLETED')),
  CONSTRAINT ck_inventory_final_plan_sha CHECK (final_plan_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_inventory_final_plan_modes CHECK (
    movement_schedule_mode IN ('AUTO', 'MANUAL')
    AND repair_schedule_mode IN ('AUTO', 'MANUAL')),
  CONSTRAINT ck_inventory_final_plan_times CHECK (updated_at >= created_at)
);

CREATE TABLE public.inventory_final_plan_entry (
  inventory_id uuid NOT NULL,
  final_plan_version bigint NOT NULL,
  finding_id uuid NOT NULL,
  finding_revision bigint NOT NULL,
  asset_id uuid,
  asset_version bigint,
  plan_fingerprint_sha256 varchar(64),
  has_work boolean NOT NULL,
  target_kind varchar(16),
  plan_order integer NOT NULL,
  priority integer,
  movement_to_repair boolean NOT NULL,
  movement_scheduled_date date,
  repair_scheduled_date date,
  collision_candidates jsonb NOT NULL,
  reconciliation_decision jsonb,
  CONSTRAINT inventory_final_plan_entry_pkey
    PRIMARY KEY (inventory_id, final_plan_version, finding_id),
  CONSTRAINT fk_inventory_final_plan_entry_session FOREIGN KEY (inventory_id)
    REFERENCES public.inventory_session(id),
  CONSTRAINT fk_inventory_final_plan_entry_finding FOREIGN KEY (inventory_id, finding_id)
    REFERENCES public.inventory_finding(inventory_id, id),
  CONSTRAINT ck_inventory_final_plan_entry_versions CHECK (
    final_plan_version >= 1 AND finding_revision >= 0
    AND (asset_version IS NULL OR asset_version >= 0) AND plan_order >= 0),
  CONSTRAINT ck_inventory_final_plan_entry_json CHECK (
    jsonb_typeof(collision_candidates) = 'array'
    AND (reconciliation_decision IS NULL OR jsonb_typeof(reconciliation_decision) = 'object')),
  CONSTRAINT ck_inventory_final_plan_entry_no_work CHECK (
    (NOT has_work
      AND plan_fingerprint_sha256 IS NULL
      AND target_kind IS NULL
      AND priority IS NULL
      AND NOT movement_to_repair
      AND movement_scheduled_date IS NULL
      AND repair_scheduled_date IS NULL
      AND collision_candidates = '[]'::jsonb
      AND reconciliation_decision IS NULL)
    OR
    (has_work
      AND asset_id IS NOT NULL
      AND asset_version IS NOT NULL
      AND plan_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
      AND target_kind IN ('ESTIMATE', 'REPAIR')
      AND priority BETWEEN 1 AND 5
      AND repair_scheduled_date IS NOT NULL
      AND (movement_to_repair = (movement_scheduled_date IS NOT NULL))))
);
CREATE UNIQUE INDEX uk_inventory_final_plan_entry_order
  ON public.inventory_final_plan_entry(inventory_id, final_plan_version, plan_order);

-- Legacy completed sessions retain their original source identity and repair result. New final
-- plans use inventoryId:finalPlanVersion:findingId and can settle either an estimate or repair.
ALTER TABLE public.inventory_publication_intent
  ADD COLUMN final_plan_version bigint,
  ADD COLUMN final_plan_sha256 varchar(64),
  ADD COLUMN target_kind varchar(16),
  ADD COLUMN target_id uuid,
  ADD COLUMN maintenance_estimate_id uuid,
  ALTER COLUMN maintenance_source_key TYPE varchar(128);

UPDATE public.inventory_publication_intent
SET target_kind = 'REPAIR', target_id = maintenance_repair_id
WHERE state = 'SUCCEEDED';

ALTER TABLE public.inventory_publication_intent
  DROP CONSTRAINT ck_inventory_publication_source,
  DROP CONSTRAINT ck_inventory_publication_success,
  ADD CONSTRAINT ck_inventory_publication_source CHECK (
    source_revision >= 1
    AND (
      (final_plan_version IS NULL AND final_plan_sha256 IS NULL
        AND maintenance_source_key = inventory_id::text || ':' || finding_id::text)
      OR
      (final_plan_version >= 1
        AND final_plan_sha256 ~ '^[0-9a-f]{64}$'
        AND maintenance_source_key = inventory_id::text || ':' || final_plan_version::text || ':' || finding_id::text)
    )),
  ADD CONSTRAINT ck_inventory_publication_target CHECK (
    (target_kind IS NULL AND target_id IS NULL AND maintenance_estimate_id IS NULL)
    OR
    (target_kind = 'REPAIR' AND maintenance_estimate_id IS NULL
      AND (target_id IS NULL OR maintenance_repair_id = target_id))
    OR
    (target_kind = 'ESTIMATE' AND maintenance_repair_id IS NULL
      AND (target_id IS NULL OR maintenance_estimate_id = target_id))),
  ADD CONSTRAINT ck_inventory_publication_success CHECK (
    (state = 'SUCCEEDED' AND target_kind IS NOT NULL AND target_id IS NOT NULL)
    OR (state <> 'SUCCEEDED' AND target_id IS NULL
      AND maintenance_repair_id IS NULL AND maintenance_estimate_id IS NULL));
