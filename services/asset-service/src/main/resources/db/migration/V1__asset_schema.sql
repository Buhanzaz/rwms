-- RWMS asset-service owned schema. New production databases are intentionally
-- empty: browser/localStorage fixtures and legacy rows are never imported here.
-- Hibernate validates this Flyway-owned schema and never creates it.

CREATE TABLE public.rental_item (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  number varchar(128) NOT NULL,
  status varchar(64) NOT NULL,
  rental_type varchar(255),
  dimensions varchar(255),
  finishing varchar(255),
  category varchar(255),
  characteristics varchar(2000),
  linoleum boolean,
  general_comment varchar(4000),
  passport_json varchar(16000) NOT NULL DEFAULT '{}',
  tags_json varchar(8000) NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT rental_item_pkey PRIMARY KEY (id),
  CONSTRAINT uk_rental_item_number UNIQUE (number),
  CONSTRAINT ck_rental_item_version CHECK (version >= 0),
  CONSTRAINT ck_rental_item_number CHECK (number ~ '^[[:alnum:]]{1,128}$'),
  CONSTRAINT ck_rental_item_status CHECK (status IN (
    'NEW','RENTED','BOOKED','REPAIR','WAITING_REPAIR_CHECK','WRITTEN_OFF',
    'CAPITAL_REPAIR','AFTER_RENT','WAITING_ESTIMATE_CONFIRMATION','SALE',
    'USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS','IN_TRANSFER')),
  CONSTRAINT ck_rental_item_passport_json CHECK (
    length(passport_json) <= 16000
    AND left(btrim(passport_json), 1) = '{'
    AND right(btrim(passport_json), 1) = '}'),
  CONSTRAINT ck_rental_item_tags_json CHECK (
    length(tags_json) <= 8000
    AND left(btrim(tags_json), 1) = '['
    AND right(btrim(tags_json), 1) = ']')
);

CREATE INDEX idx_rental_item_warehouse_number ON public.rental_item(warehouse_id, number);
CREATE INDEX idx_rental_item_warehouse_status ON public.rental_item(warehouse_id, status);

CREATE TABLE public.asset_classifier (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  classifier_type varchar(32) NOT NULL,
  parent_id uuid,
  code varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  sort_order integer,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT asset_classifier_pkey PRIMARY KEY (id),
  CONSTRAINT uk_asset_classifier_type_code UNIQUE (classifier_type, code),
  CONSTRAINT ck_asset_classifier_version CHECK (version >= 0),
  CONSTRAINT ck_asset_classifier_type CHECK (classifier_type IN ('CATEGORY','SUBCATEGORY','TYPE','CONDITION')),
  CONSTRAINT ck_asset_classifier_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_asset_classifier_sort_order CHECK (sort_order IS NULL OR sort_order >= 0),
  CONSTRAINT fk_asset_classifier_parent FOREIGN KEY (parent_id) REFERENCES public.asset_classifier(id)
);

CREATE TABLE public.asset_attribute_definition (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  code varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  data_type varchar(16) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  sort_order integer,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT asset_attribute_definition_pkey PRIMARY KEY (id),
  CONSTRAINT uk_asset_attribute_definition_code UNIQUE (code),
  CONSTRAINT ck_asset_attribute_definition_version CHECK (version >= 0),
  CONSTRAINT ck_asset_attribute_definition_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_asset_attribute_definition_type CHECK (data_type IN ('STRING','TEXT','NUMBER','BOOLEAN','ENUM')),
  CONSTRAINT ck_asset_attribute_definition_sort CHECK (sort_order IS NULL OR sort_order >= 0)
);

CREATE TABLE public.asset_attribute_option (
  id uuid NOT NULL,
  definition_id uuid NOT NULL,
  code varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  sort_order integer,
  CONSTRAINT asset_attribute_option_pkey PRIMARY KEY (id),
  CONSTRAINT uk_asset_attribute_option_code UNIQUE (definition_id, code),
  CONSTRAINT ck_asset_attribute_option_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_asset_attribute_option_sort CHECK (sort_order IS NULL OR sort_order >= 0),
  CONSTRAINT fk_asset_attribute_option_definition FOREIGN KEY (definition_id)
    REFERENCES public.asset_attribute_definition(id)
);

CREATE TABLE public.asset_classifier_attribute (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  classifier_id uuid,
  attribute_definition_id uuid NOT NULL,
  active boolean NOT NULL DEFAULT true,
  sort_order integer,
  CONSTRAINT asset_classifier_attribute_pkey PRIMARY KEY (id),
  CONSTRAINT uk_asset_classifier_attribute UNIQUE NULLS NOT DISTINCT (classifier_id, attribute_definition_id),
  CONSTRAINT ck_asset_classifier_attribute_version CHECK (version >= 0),
  CONSTRAINT ck_asset_classifier_attribute_sort CHECK (sort_order IS NULL OR sort_order >= 0),
  CONSTRAINT fk_asset_classifier_attribute_classifier FOREIGN KEY (classifier_id)
    REFERENCES public.asset_classifier(id),
  CONSTRAINT fk_asset_classifier_attribute_definition FOREIGN KEY (attribute_definition_id)
    REFERENCES public.asset_attribute_definition(id)
);

CREATE TABLE public.rental_item_attribute_value (
  rental_item_id uuid NOT NULL,
  attribute_definition_id uuid NOT NULL,
  value_string varchar(2000),
  value_number numeric(20,6),
  value_boolean boolean,
  option_id uuid,
  CONSTRAINT rental_item_attribute_value_pkey PRIMARY KEY (rental_item_id, attribute_definition_id),
  CONSTRAINT fk_rental_item_attribute_value_item FOREIGN KEY (rental_item_id)
    REFERENCES public.rental_item(id),
  CONSTRAINT fk_rental_item_attribute_value_definition FOREIGN KEY (attribute_definition_id)
    REFERENCES public.asset_attribute_definition(id),
  CONSTRAINT fk_rental_item_attribute_value_option FOREIGN KEY (option_id)
    REFERENCES public.asset_attribute_option(id)
);

CREATE TABLE public.rental_tag (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  code varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  sort_order integer,
  CONSTRAINT rental_tag_pkey PRIMARY KEY (id),
  CONSTRAINT uk_rental_tag_code UNIQUE (code),
  CONSTRAINT ck_rental_tag_version CHECK (version >= 0),
  CONSTRAINT ck_rental_tag_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_rental_tag_sort CHECK (sort_order IS NULL OR sort_order >= 0)
);

CREATE TABLE public.rental_item_tag (
  rental_item_id uuid NOT NULL,
  tag_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT rental_item_tag_pkey PRIMARY KEY (rental_item_id, tag_id),
  CONSTRAINT fk_rental_item_tag_item FOREIGN KEY (rental_item_id) REFERENCES public.rental_item(id),
  CONSTRAINT fk_rental_item_tag_tag FOREIGN KEY (tag_id) REFERENCES public.rental_tag(id)
);

CREATE TABLE public.rental_item_note (
  id uuid NOT NULL,
  rental_item_id uuid NOT NULL,
  actor_subject_id uuid NOT NULL,
  note_text varchar(4000) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT rental_item_note_pkey PRIMARY KEY (id),
  CONSTRAINT ck_rental_item_note_text CHECK (length(btrim(note_text)) BETWEEN 1 AND 4000),
  CONSTRAINT fk_rental_item_note_item FOREIGN KEY (rental_item_id) REFERENCES public.rental_item(id)
);
CREATE INDEX idx_rental_item_note_item_created ON public.rental_item_note(rental_item_id, created_at, id);

CREATE TABLE public.equipment_catalog_item (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  code varchar(64) NOT NULL,
  name varchar(255) NOT NULL,
  category varchar(32) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  comment varchar(2000),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT equipment_catalog_item_pkey PRIMARY KEY (id),
  CONSTRAINT uk_equipment_catalog_item_code UNIQUE (code),
  CONSTRAINT ck_equipment_catalog_item_version CHECK (version >= 0),
  CONSTRAINT ck_equipment_catalog_item_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
  CONSTRAINT ck_equipment_catalog_item_category CHECK (category IN ('FURNITURE','ELECTRICAL','OTHER'))
);

CREATE TABLE public.equipment_balance (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  equipment_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  rental_item_id uuid,
  location_kind varchar(32) NOT NULL,
  quantity bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT equipment_balance_pkey PRIMARY KEY (id),
  CONSTRAINT ck_equipment_balance_version CHECK (version >= 0),
  CONSTRAINT ck_equipment_balance_quantity CHECK (quantity >= 0),
  CONSTRAINT ck_equipment_balance_kind CHECK (location_kind IN (
    'STOCK','CABIN_NON_RENTED','CABIN_RENTED','WRITTEN_OFF','LOST')),
  CONSTRAINT ck_equipment_balance_location CHECK (
    (location_kind IN ('CABIN_NON_RENTED','CABIN_RENTED') AND rental_item_id IS NOT NULL)
    OR (location_kind IN ('STOCK','WRITTEN_OFF','LOST') AND rental_item_id IS NULL)
  ),
  CONSTRAINT fk_equipment_balance_catalog FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
  CONSTRAINT fk_equipment_balance_item FOREIGN KEY (rental_item_id) REFERENCES public.rental_item(id)
);
CREATE UNIQUE INDEX uk_equipment_balance_warehouse_bucket
  ON public.equipment_balance(equipment_id, warehouse_id, location_kind)
  WHERE rental_item_id IS NULL;
CREATE UNIQUE INDEX uk_equipment_balance_cabin_bucket
  ON public.equipment_balance(equipment_id, warehouse_id, rental_item_id, location_kind)
  WHERE rental_item_id IS NOT NULL;
CREATE INDEX idx_equipment_balance_equipment_warehouse ON public.equipment_balance(equipment_id, warehouse_id);

CREATE TABLE public.equipment_movement (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  equipment_id uuid NOT NULL,
  source_balance_id uuid NOT NULL,
  target_balance_id uuid NOT NULL,
  quantity bigint NOT NULL,
  movement_kind varchar(40) NOT NULL,
  occurred_at timestamptz NOT NULL,
  actor_subject_id uuid NOT NULL,
  CONSTRAINT equipment_movement_pkey PRIMARY KEY (id),
  CONSTRAINT ck_equipment_movement_version CHECK (version >= 0),
  CONSTRAINT ck_equipment_movement_quantity CHECK (quantity > 0),
  CONSTRAINT ck_equipment_movement_distinct_balances CHECK (source_balance_id <> target_balance_id),
  CONSTRAINT ck_equipment_movement_kind CHECK (movement_kind IN (
    'STOCK_TO_CABIN','CABIN_TO_STOCK','CABIN_TO_CABIN','WAREHOUSE_TO_WAREHOUSE','WRITE_OFF','LOSS')),
  CONSTRAINT fk_equipment_movement_catalog FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id),
  CONSTRAINT fk_equipment_movement_source FOREIGN KEY (source_balance_id) REFERENCES public.equipment_balance(id),
  CONSTRAINT fk_equipment_movement_target FOREIGN KEY (target_balance_id) REFERENCES public.equipment_balance(id)
);

CREATE TABLE public.equipment_movement_ledger (
  movement_id uuid NOT NULL,
  line_no smallint NOT NULL,
  balance_id uuid NOT NULL,
  quantity_delta bigint NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT equipment_movement_ledger_pkey PRIMARY KEY (movement_id, line_no),
  CONSTRAINT ck_equipment_movement_ledger_line CHECK (line_no IN (1,2)),
  CONSTRAINT ck_equipment_movement_ledger_delta CHECK (quantity_delta <> 0),
  CONSTRAINT fk_equipment_movement_ledger_movement FOREIGN KEY (movement_id)
    REFERENCES public.equipment_movement(id),
  CONSTRAINT fk_equipment_movement_ledger_balance FOREIGN KEY (balance_id)
    REFERENCES public.equipment_balance(id)
);

CREATE TABLE public.equipment_allocation_hold (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  equipment_id uuid NOT NULL,
  warehouse_id uuid NOT NULL,
  owner_type varchar(64) NOT NULL,
  owner_id varchar(128) NOT NULL,
  quantity bigint NOT NULL,
  state varchar(16) NOT NULL,
  idempotency_key uuid NOT NULL,
  expires_at timestamptz NOT NULL,
  released_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT equipment_allocation_hold_pkey PRIMARY KEY (id),
  CONSTRAINT uk_equipment_hold_owner_idempotency UNIQUE (owner_type, owner_id, idempotency_key),
  CONSTRAINT ck_equipment_hold_version CHECK (version >= 0),
  CONSTRAINT ck_equipment_hold_owner CHECK (owner_type ~ '^[A-Z][A-Z0-9_]{0,63}$' AND length(btrim(owner_id)) BETWEEN 1 AND 128),
  CONSTRAINT ck_equipment_hold_quantity CHECK (quantity > 0),
  CONSTRAINT ck_equipment_hold_state CHECK (state IN ('ACTIVE','RELEASED','EXPIRED')),
  CONSTRAINT ck_equipment_hold_release CHECK (
    (state = 'ACTIVE' AND released_at IS NULL) OR (state <> 'ACTIVE' AND released_at IS NOT NULL)),
  CONSTRAINT fk_equipment_hold_catalog FOREIGN KEY (equipment_id) REFERENCES public.equipment_catalog_item(id)
);
CREATE INDEX idx_equipment_hold_active ON public.equipment_allocation_hold(equipment_id, warehouse_id, expires_at)
  WHERE state = 'ACTIVE';

CREATE TABLE public.operation_lease (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  rental_item_id uuid NOT NULL,
  owner_type varchar(64) NOT NULL,
  owner_id varchar(128) NOT NULL,
  fencing_token bigint NOT NULL,
  state varchar(16) NOT NULL,
  idempotency_key uuid NOT NULL,
  expires_at timestamptz NOT NULL,
  released_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT operation_lease_pkey PRIMARY KEY (id),
  CONSTRAINT uk_operation_lease_owner_idempotency UNIQUE (owner_type, owner_id, idempotency_key),
  CONSTRAINT uk_operation_lease_fencing UNIQUE (rental_item_id, fencing_token),
  CONSTRAINT ck_operation_lease_version CHECK (version >= 0),
  CONSTRAINT ck_operation_lease_owner CHECK (owner_type ~ '^[A-Z][A-Z0-9_]{0,63}$' AND length(btrim(owner_id)) BETWEEN 1 AND 128),
  CONSTRAINT ck_operation_lease_fencing CHECK (fencing_token > 0),
  CONSTRAINT ck_operation_lease_state CHECK (state IN ('ACTIVE','RELEASED','EXPIRED')),
  CONSTRAINT ck_operation_lease_release CHECK (
    (state = 'ACTIVE' AND released_at IS NULL) OR (state <> 'ACTIVE' AND released_at IS NOT NULL)),
  CONSTRAINT fk_operation_lease_item FOREIGN KEY (rental_item_id) REFERENCES public.rental_item(id)
);
CREATE UNIQUE INDEX uk_operation_lease_active_item ON public.operation_lease(rental_item_id)
  WHERE state = 'ACTIVE';

CREATE TABLE public.event_stream_head (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  current_version bigint NOT NULL,
  last_event_id uuid NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT event_stream_head_pkey PRIMARY KEY (aggregate_type, aggregate_id),
  CONSTRAINT ck_asset_event_stream_head_type CHECK (aggregate_type IN (
    'RENTAL_ITEM','EQUIPMENT_CATALOG','EQUIPMENT_BALANCE','EQUIPMENT_MOVEMENT',
    'EQUIPMENT_ALLOCATION_HOLD','OPERATION_LEASE')),
  CONSTRAINT ck_asset_event_stream_head_version CHECK (current_version >= 0)
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
  CONSTRAINT uk_asset_domain_event_stream_version UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT uk_asset_domain_event_identity UNIQUE (event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT fk_asset_domain_event_stream FOREIGN KEY (aggregate_type, aggregate_id)
    REFERENCES public.event_stream_head(aggregate_type, aggregate_id),
  CONSTRAINT ck_asset_domain_event_type CHECK (event_type IN (
    'asset.rental-item.created.v1','asset.rental-item.passport-changed.v1',
    'asset.rental-item.status-changed.v1','asset.rental-item.warehouse-changed.v1',
    'asset.rental-item.general-comment-changed.v1','asset.rental-item.manual-note-added.v1',
    'asset.equipment-catalog.created.v1','asset.equipment-catalog.changed.v1',
    'asset.equipment-balance.changed.v1','asset.equipment-movement.transferred.v1',
    'asset.equipment-movement.written-off.v1','asset.equipment-movement.lost.v1','asset.equipment-allocation-hold.acquired.v1',
    'asset.equipment-allocation-hold.renewed.v1','asset.equipment-allocation-hold.released.v1',
    'asset.equipment-allocation-hold.expired.v1','asset.operation-lease.acquired.v1',
    'asset.operation-lease.renewed.v1','asset.operation-lease.released.v1',
    'asset.operation-lease.expired.v1')),
  CONSTRAINT ck_asset_domain_event_version CHECK (aggregate_version >= 0 AND event_version = 1),
  CONSTRAINT ck_asset_domain_event_payload CHECK (jsonb_typeof(payload) = 'object' AND payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_asset_domain_event_actor CHECK (actor_ref IS NULL OR jsonb_typeof(actor_ref) = 'object'),
  CONSTRAINT ck_asset_domain_event_baseline CHECK (
    (NOT baseline AND occurred_at IS NOT NULL) OR (baseline AND occurred_at IS NULL))
);
CREATE INDEX idx_asset_domain_event_stream ON public.domain_event(aggregate_type, aggregate_id, aggregate_version);

CREATE TABLE public.aggregate_snapshot (
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  state jsonb NOT NULL,
  state_sha256 char(64) NOT NULL,
  recorded_at timestamptz NOT NULL,
  CONSTRAINT aggregate_snapshot_pkey PRIMARY KEY (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_asset_snapshot_stream FOREIGN KEY (aggregate_type, aggregate_id)
    REFERENCES public.event_stream_head(aggregate_type, aggregate_id),
  CONSTRAINT ck_asset_snapshot_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_asset_snapshot_state CHECK (jsonb_typeof(state) = 'object' AND state_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE public.projection_checkpoint (
  projection_name varchar(128) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  projection_sha256 char(64) NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projection_name, aggregate_type, aggregate_id),
  CONSTRAINT ck_asset_projection_checkpoint_version CHECK (aggregate_version >= 0),
  CONSTRAINT ck_asset_projection_checkpoint_hash CHECK (projection_sha256 ~ '^[0-9a-f]{64}$')
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
  CONSTRAINT uk_asset_outbox_stream_version UNIQUE (aggregate_type, aggregate_id, aggregate_version),
  CONSTRAINT fk_asset_outbox_event FOREIGN KEY (event_id, aggregate_type, aggregate_id, aggregate_version, event_type)
    REFERENCES public.domain_event(event_id, aggregate_type, aggregate_id, aggregate_version, event_type),
  CONSTRAINT ck_asset_outbox_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED','DLT','QUARANTINED')),
  CONSTRAINT ck_asset_outbox_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_asset_outbox_body CHECK (jsonb_typeof(envelope_body) = 'object' AND envelope_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_asset_outbox_topic CHECK (topic IN (
    'rwms.asset.rental-item.v1','rwms.asset.equipment-catalog.v1','rwms.asset.equipment-balance.v1',
    'rwms.asset.equipment-movement.v1','rwms.asset.equipment-allocation-hold.v1','rwms.asset.operation-lease.v1')),
  CONSTRAINT ck_asset_outbox_lease CHECK (
    (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
  CONSTRAINT ck_asset_outbox_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT ck_asset_outbox_dlt CHECK ((status = 'DLT') = (dlt_at IS NOT NULL))
);
CREATE INDEX idx_asset_outbox_pending ON public.outbox_event(next_attempt_at, created_at, event_id) WHERE status = 'PENDING';

CREATE TABLE public.inbox_message (
  consumer_group varchar(128) NOT NULL,
  event_id uuid NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL,
  payload_sha256 char(64) NOT NULL,
  status varchar(24) NOT NULL,
  attempt_count integer NOT NULL DEFAULT 0,
  received_at timestamptz NOT NULL,
  processed_at timestamptz,
  next_attempt_at timestamptz,
  dlt_at timestamptz,
  quarantine_reason varchar(128),
  CONSTRAINT inbox_message_pkey PRIMARY KEY (consumer_group, event_id),
  CONSTRAINT ck_asset_inbox_status CHECK (status IN ('RECEIVED','PROCESSED','RETRY','DLT','QUARANTINED')),
  CONSTRAINT ck_asset_inbox_version CHECK (aggregate_version >= 0 AND attempt_count >= 0),
  CONSTRAINT ck_asset_inbox_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

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
  CONSTRAINT ck_asset_consumer_checkpoint CHECK (
    (last_aggregate_version = -1 AND last_event_id IS NULL) OR (last_aggregate_version >= 0 AND last_event_id IS NOT NULL)),
  CONSTRAINT ck_asset_consumer_checkpoint_blocked CHECK (
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
  CONSTRAINT uk_asset_version_gap_event UNIQUE (consumer_group, received_event_id),
  CONSTRAINT ck_asset_version_gap_versions CHECK (expected_version >= 0 AND received_version > expected_version),
  CONSTRAINT ck_asset_version_gap_status CHECK (status IN ('OPEN','RESOLVED')),
  CONSTRAINT ck_asset_version_gap_reason CHECK (reason_code = 'AGGREGATE_VERSION_GAP')
);

CREATE TABLE public.sanitized_dead_letter (
  dlt_id uuid NOT NULL,
  destination varchar(224) NOT NULL,
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
  CONSTRAINT sanitized_dead_letter_pkey PRIMARY KEY (dlt_id),
  CONSTRAINT ck_asset_dlt_failure CHECK (failure_code IN ('VALIDATION_REJECTED','PROCESSING_FAILED','VERSION_GAP')),
  CONSTRAINT ck_asset_dlt_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED','FAILED')),
  CONSTRAINT ck_asset_dlt_hashes CHECK (message_sha256 ~ '^[0-9a-f]{64}$' AND body_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_asset_dlt_body CHECK (jsonb_typeof(safe_body) = 'object')
);

CREATE TABLE public.asset_idempotency_record (
  subject_id uuid NOT NULL,
  command_scope varchar(96) NOT NULL,
  idempotency_key uuid NOT NULL,
  request_sha256 char(64) NOT NULL,
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT asset_idempotency_record_pkey PRIMARY KEY (subject_id, command_scope, idempotency_key),
  CONSTRAINT ck_asset_idempotency_scope CHECK (command_scope ~ '^[a-z][a-z0-9.-]{0,95}$'),
  CONSTRAINT ck_asset_idempotency_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_asset_idempotency_response CHECK (response_status BETWEEN 200 AND 299 AND jsonb_typeof(response_body) = 'object'),
  CONSTRAINT ck_asset_idempotency_expiry CHECK (expires_at > created_at)
);
CREATE INDEX idx_asset_idempotency_expiry ON public.asset_idempotency_record(expires_at);

CREATE FUNCTION public.prevent_asset_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'asset append-only evidence cannot be updated or deleted';
END;
$$;

CREATE TRIGGER trg_rental_item_note_immutable
BEFORE UPDATE OR DELETE ON public.rental_item_note
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
CREATE TRIGGER trg_equipment_movement_immutable
BEFORE UPDATE OR DELETE ON public.equipment_movement
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
CREATE TRIGGER trg_equipment_movement_ledger_immutable
BEFORE UPDATE OR DELETE ON public.equipment_movement_ledger
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();
CREATE TRIGGER trg_domain_event_immutable
BEFORE UPDATE OR DELETE ON public.domain_event
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_append_only_mutation();

CREATE FUNCTION public.prevent_asset_hard_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'asset canonical records cannot be hard deleted';
END;
$$;
CREATE TRIGGER trg_rental_item_no_delete
BEFORE DELETE ON public.rental_item
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_hard_delete();
CREATE TRIGGER trg_equipment_catalog_no_delete
BEFORE DELETE ON public.equipment_catalog_item
FOR EACH ROW EXECUTE FUNCTION public.prevent_asset_hard_delete();
