-- Only normalized parser output and operator decisions are durable. The raw
-- HTML is deliberately absent from this schema.
CREATE TABLE public.rental_item_html_import (
  id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  warehouse_id uuid NOT NULL,
  actor_subject_id uuid NOT NULL,
  idempotency_key uuid NOT NULL,
  source_sha256 varchar(64) NOT NULL,
  state varchar(40) NOT NULL,
  row_count integer NOT NULL,
  selected_count integer NOT NULL,
  invalid_count integer NOT NULL,
  unresolved_count integer NOT NULL,
  media_link_count integer NOT NULL,
  warning_count integer NOT NULL,
  plan_json varchar(1000000) NOT NULL DEFAULT '{}',
  media_job_id uuid,
  failure_code varchar(128),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT rental_item_html_import_pkey PRIMARY KEY (id),
  CONSTRAINT uk_rental_item_html_import_idempotency
    UNIQUE (actor_subject_id, idempotency_key),
  CONSTRAINT ck_rental_item_html_import_version CHECK (version >= 0),
  CONSTRAINT ck_rental_item_html_import_sha CHECK (
    source_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_rental_item_html_import_state CHECK (state IN (
    'DRAFT','REVIEW_REQUIRED','READY','COMMITTING','ASSETS_COMMITTED',
    'MEDIA_IMPORTING','COMPLETED','COMPLETED_WITH_WARNINGS','FAILED')),
  CONSTRAINT ck_rental_item_html_import_counts CHECK (
    row_count >= 0
    AND selected_count >= 0
    AND invalid_count >= 0
    AND unresolved_count >= 0
    AND media_link_count >= 0
    AND warning_count >= 0),
  CONSTRAINT ck_rental_item_html_import_plan CHECK (
    length(plan_json) <= 1000000
    AND left(btrim(plan_json), 1) = '{'
    AND right(btrim(plan_json), 1) = '}')
);
CREATE INDEX idx_rental_item_html_import_warehouse_updated
  ON public.rental_item_html_import(warehouse_id, updated_at DESC, id);

CREATE TABLE public.rental_item_html_import_row (
  id uuid NOT NULL,
  import_id uuid NOT NULL,
  source_row_id varchar(64) NOT NULL,
  source_position integer NOT NULL,
  source_number varchar(512),
  proposed_number varchar(128),
  identity_match_key varchar(128),
  target_rental_item_id uuid,
  action varchar(16) NOT NULL,
  has_photo_link boolean NOT NULL,
  parsed_json varchar(32000) NOT NULL,
  decision_json varchar(16000) NOT NULL DEFAULT '{}',
  diagnostic_codes_json varchar(8000) NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT rental_item_html_import_row_pkey PRIMARY KEY (id),
  CONSTRAINT uk_rental_item_html_import_source UNIQUE (import_id, source_row_id),
  CONSTRAINT ck_rental_item_html_import_row_position CHECK (source_position >= 0),
  CONSTRAINT ck_rental_item_html_import_row_action CHECK (
    action IN ('CREATE','MERGE','EXCLUDE','REVIEW')),
  CONSTRAINT ck_rental_item_html_import_row_parsed CHECK (
    length(parsed_json) <= 32000
    AND left(btrim(parsed_json), 1) = '{'
    AND right(btrim(parsed_json), 1) = '}'),
  CONSTRAINT ck_rental_item_html_import_row_decision CHECK (
    length(decision_json) <= 16000
    AND left(btrim(decision_json), 1) = '{'
    AND right(btrim(decision_json), 1) = '}'),
  CONSTRAINT ck_rental_item_html_import_row_diagnostics CHECK (
    length(diagnostic_codes_json) <= 8000
    AND left(btrim(diagnostic_codes_json), 1) = '['
    AND right(btrim(diagnostic_codes_json), 1) = ']'),
  CONSTRAINT fk_rental_item_html_import_row_import FOREIGN KEY (import_id)
    REFERENCES public.rental_item_html_import(id) ON DELETE CASCADE,
  CONSTRAINT fk_rental_item_html_import_row_target FOREIGN KEY (target_rental_item_id)
    REFERENCES public.rental_item(id)
);
CREATE INDEX idx_rental_item_html_import_row_order
  ON public.rental_item_html_import_row(import_id, source_position, id);
CREATE INDEX idx_rental_item_html_import_row_target
  ON public.rental_item_html_import_row(target_rental_item_id)
  WHERE target_rental_item_id IS NOT NULL;
